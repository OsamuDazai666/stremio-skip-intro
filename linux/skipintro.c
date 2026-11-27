// libskipintro.so - adds skip-intro.plugin.js to the official Stremio app for Linux.
//
// Stremio for Linux (Flatpak com.stremio.Stremio, github.com/Stremio/stremio-linux-shell) is
// GTK4 + WebKitGTK 6 + libmpv. It adds its IPC bridge to the page with
// webkit_user_content_manager_add_script(). The installer loads this library into Stremio with
//     flatpak override --user --env=LD_PRELOAD=<this file> com.stremio.Stremio
// and when Stremio adds its own script, we add the plugin to the same user content manager
// (document end, top frame only). The Stremio app itself is not modified.
//
// Files: $XDG_DATA_HOME/stremio-skip-intro/  (inside the Flatpak: ~/.var/app/com.stremio.Stremio/data/)
//   skip-intro.plugin.js   the plugin (read once per app launch)
//   skipintro.log          log lines (only when SKIPINTRO_DEBUG=1)
// Developer mode (SKIPINTRO_DEV=1, never set by the installer):
//   also runs dev-test.js from the same folder in the page, after the plugin.
//
// No build-time dependency on GTK or WebKit headers: the few functions used are looked up at
// run time, so the library builds with any C compiler and works with any WebKitGTK 6.
//
// Build: linux/build.sh

#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

typedef struct _WebKitUserContentManager WebKitUserContentManager;
typedef struct _WebKitUserScript WebKitUserScript;
typedef struct _JSCValue JSCValue;

// WebKitUserContentInjectedFrames / WebKitUserScriptInjectionTime
enum { INJECT_ALL_FRAMES = 0, INJECT_TOP_FRAME = 1 };
enum { INJECT_AT_DOCUMENT_START = 0, INJECT_AT_DOCUMENT_END = 1 };

typedef void (*add_script_fn)(WebKitUserContentManager *, WebKitUserScript *);
typedef WebKitUserScript *(*script_new_fn)(const char *, int, int, const char *const *, const char *const *);
typedef void (*script_unref_fn)(WebKitUserScript *);
typedef int (*register_handler_fn)(WebKitUserContentManager *, const char *, const char *);
typedef unsigned long (*signal_connect_fn)(void *, const char *, void (*)(void), void *, void *, int);
typedef char *(*jsc_to_string_fn)(JSCValue *);
typedef void (*g_free_fn)(void *);

static int active;      // 1 only inside the Stremio process
static int debug_mode;  // SKIPINTRO_DEBUG or SKIPINTRO_DEV
static int dev_mode;    // SKIPINTRO_DEV
static char dir[PATH_MAX];

#define MAX_MANAGERS 8
static WebKitUserContentManager *done_managers[MAX_MANAGERS];

static int env_flag(const char *name) {
    const char *v = getenv(name);
    return v && (strcmp(v, "1") == 0 || strcmp(v, "true") == 0);
}

static void si_log(const char *fmt, ...) {
    if (!debug_mode) return;
    char path[PATH_MAX];
    snprintf(path, sizeof path, "%s/skipintro.log", dir);
    FILE *f = fopen(path, "a");
    if (!f) return;
    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    fprintf(f, "%02d:%02d:%02d  ", tm.tm_hour, tm.tm_min, tm.tm_sec);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static char *read_file(const char *name, size_t *len_out) {
    char path[PATH_MAX];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long len = ftell(f);
    if (len <= 0 || len > 16 * 1024 * 1024) { fclose(f); return NULL; }
    rewind(f);
    char *buf = malloc((size_t)len + 1);
    if (!buf) { fclose(f); return NULL; }
    size_t n = fread(buf, 1, (size_t)len, f);
    fclose(f);
    buf[n] = '\0';
    if (len_out) *len_out = n;
    return buf;
}

// Wraps plugin source so a failure to start never breaks Stremio's page.
static char *wrap(const char *source, int debug) {
    const char *prefix = debug ? "window.__SKIPINTRO_DEBUG = true;\n" : "";
    const char *head = "try {\n";
    const char *tail = "\n} catch (e) { console.error('[SkipIntro] failed to start', e); }\n";
    size_t n = strlen(prefix) + strlen(head) + strlen(source) + strlen(tail) + 1;
    char *out = malloc(n);
    if (!out) return NULL;
    snprintf(out, n, "%s%s%s%s", prefix, head, source, tail);
    return out;
}

static void on_page_message(WebKitUserContentManager *manager, JSCValue *value, void *data) {
    (void)manager;
    (void)data;
    static jsc_to_string_fn to_string;
    static g_free_fn g_free_ptr;
    if (!to_string) to_string = (jsc_to_string_fn)dlsym(RTLD_DEFAULT, "jsc_value_to_string");
    if (!g_free_ptr) g_free_ptr = (g_free_fn)dlsym(RTLD_DEFAULT, "g_free");
    if (!to_string || !value) return;
    char *s = to_string(value);
    if (!s) return;
    si_log("[page] %s", s);
    if (g_free_ptr) g_free_ptr(s);
}

static void add_user_script(add_script_fn add, WebKitUserContentManager *manager, const char *source) {
    script_new_fn script_new = (script_new_fn)dlsym(RTLD_DEFAULT, "webkit_user_script_new");
    script_unref_fn script_unref = (script_unref_fn)dlsym(RTLD_DEFAULT, "webkit_user_script_unref");
    if (!script_new) {
        si_log("injector: webkit_user_script_new not found");
        return;
    }
    WebKitUserScript *script = script_new(source, INJECT_TOP_FRAME, INJECT_AT_DOCUMENT_END, NULL, NULL);
    if (!script) return;
    add(manager, script);
    if (script_unref) script_unref(script);
}

static void install_into(add_script_fn add, WebKitUserContentManager *manager) {
    for (int i = 0; i < MAX_MANAGERS; i++) {
        if (done_managers[i] == manager) return;
    }
    for (int i = 0; i < MAX_MANAGERS; i++) {
        if (!done_managers[i]) {
            done_managers[i] = manager;
            break;
        }
    }

    size_t len = 0;
    char *source = read_file("skip-intro.plugin.js", &len);
    if (!source) {
        fprintf(stderr, "[SkipIntro] plugin not found in %s\n", dir);
        si_log("injector: plugin not found in %s", dir);
        return;
    }
    char *wrapped = wrap(source, debug_mode);
    free(source);
    if (!wrapped) return;

    if (debug_mode) {
        register_handler_fn reg = (register_handler_fn)dlsym(RTLD_DEFAULT, "webkit_user_content_manager_register_script_message_handler");
        signal_connect_fn connect = (signal_connect_fn)dlsym(RTLD_DEFAULT, "g_signal_connect_data");
        if (reg && connect) {
            reg(manager, "skipintro", NULL);
            connect(manager, "script-message-received::skipintro", (void (*)(void))on_page_message, NULL, NULL, 0);
        }
    }

    add_user_script(add, manager, wrapped);
    free(wrapped);
    si_log("injector: plugin (%zu bytes) added to the web view", len);

    if (dev_mode) {
        char *test = read_file("dev-test.js", NULL);
        if (test) {
            char *wrapped_test = wrap(test, 1);
            free(test);
            if (wrapped_test) {
                add_user_script(add, manager, wrapped_test);
                free(wrapped_test);
                si_log("injector: dev-test.js added");
            }
        }
    }
}

// Interposed: Stremio calls this to add its IPC bridge script.
void webkit_user_content_manager_add_script(WebKitUserContentManager *manager, WebKitUserScript *script) {
    static add_script_fn orig;
    if (!orig) orig = (add_script_fn)dlsym(RTLD_NEXT, "webkit_user_content_manager_add_script");
    if (!orig) return;
    orig(manager, script);
    if (active && manager) install_into(orig, manager);
}

static int is_stremio_process(void) {
    char exe[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (n <= 0) return 0;
    exe[n] = '\0';
    const char *base = strrchr(exe, '/');
    base = base ? base + 1 : exe;
    return strcmp(base, "stremio") == 0;
}

__attribute__((constructor))
static void si_init(void) {
    // Only act inside Stremio. The library is also loaded into the launcher script's shell.
    if (!is_stremio_process()) return;
    active = 1;

    const char *custom = getenv("SKIPINTRO_DIR");
    const char *data = getenv("XDG_DATA_HOME");
    const char *home = getenv("HOME");
    if (custom && *custom) snprintf(dir, sizeof dir, "%s", custom);
    else if (data && *data) snprintf(dir, sizeof dir, "%s/stremio-skip-intro", data);
    else snprintf(dir, sizeof dir, "%s/.local/share/stremio-skip-intro", home ? home : "/tmp");

    debug_mode = env_flag("SKIPINTRO_DEBUG") || env_flag("SKIPINTRO_DEV");
    dev_mode = env_flag("SKIPINTRO_DEV");
    si_log("injector: loaded into pid %d", (int)getpid());

    // Do not pass the library on to processes Stremio starts (WebKit's helpers, the server).
    unsetenv("LD_PRELOAD");
}
