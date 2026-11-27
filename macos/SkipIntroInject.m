// SkipIntroInject.dylib - adds skip-intro.plugin.js to the official Stremio 5 macOS app.
//
// Stremio's app is a WKWebView (via wry) + libmpv. Its code signature allows
// DYLD_INSERT_LIBRARIES (com.apple.security.cs.allow-dyld-environment-variables +
// disable-library-validation), so the "Stremio Skip Intro" launcher starts Stremio with this
// library. When Stremio creates its WKWebView, we add the plugin as a WKUserScript
// (document end, main frame only). The Stremio app bundle itself is not modified.
//
// Files: ~/Library/Application Support/StremioSkipIntro/
//   skip-intro.plugin.js   the plugin (read once per app launch)
//   skipintro.log          plugin log lines (only when SKIPINTRO_DEBUG=1)
// Developer mode (SKIPINTRO_DEV=1, never set by the installer):
//   makes the web view inspectable in Safari and evaluates eval.js -> eval.out (for remote testing).
//
// Build: macos/build.sh  (universal arm64 + x86_64)

#import <Foundation/Foundation.h>
#import <WebKit/WebKit.h>
#import <objc/runtime.h>

static NSString *SIDir(void) {
    return [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/StremioSkipIntro"];
}

static BOOL SIEnvFlag(const char *name) {
    const char *v = getenv(name);
    return v && (strcmp(v, "1") == 0 || strcmp(v, "true") == 0);
}

static void SIAppendLog(NSString *line) {
    NSString *path = [SIDir() stringByAppendingPathComponent:@"skipintro.log"];
    NSDateFormatter *f = [NSDateFormatter new];
    f.dateFormat = @"HH:mm:ss.SSS";
    NSString *entry = [NSString stringWithFormat:@"%@  %@\n", [f stringFromDate:[NSDate date]], line];
    NSFileHandle *h = [NSFileHandle fileHandleForWritingAtPath:path];
    if (!h) {
        [entry writeToFile:path atomically:NO encoding:NSUTF8StringEncoding error:nil];
        return;
    }
    [h seekToEndOfFile];
    [h writeData:[entry dataUsingEncoding:NSUTF8StringEncoding]];
    [h closeFile];
}

@interface SIMessageHandler : NSObject <WKScriptMessageHandler>
@end

@implementation SIMessageHandler
- (void)userContentController:(WKUserContentController *)controller didReceiveScriptMessage:(WKScriptMessage *)message {
    SIAppendLog([NSString stringWithFormat:@"[page] %@", message.body]);
}
@end

static __weak WKWebView *gWebView = nil;
static char kSIInstalledKey;

static void SIInstallInto(WKWebViewConfiguration *config) {
    if (!config) return;
    WKUserContentController *ucc = config.userContentController;
    if (!ucc || objc_getAssociatedObject(ucc, &kSIInstalledKey)) return;

    NSString *pluginPath = [SIDir() stringByAppendingPathComponent:@"skip-intro.plugin.js"];
    NSError *err = nil;
    NSString *source = [NSString stringWithContentsOfFile:pluginPath encoding:NSUTF8StringEncoding error:&err];
    if (!source) {
        NSLog(@"[SkipIntro] plugin not found at %@: %@", pluginPath, err);
        return;
    }
    objc_setAssociatedObject(ucc, &kSIInstalledKey, @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    BOOL debug = SIEnvFlag("SKIPINTRO_DEBUG") || SIEnvFlag("SKIPINTRO_DEV");
    NSString *prefix = debug ? @"window.__SKIPINTRO_DEBUG = true;\n" : @"";
    NSString *wrapped = [NSString stringWithFormat:@"%@try {\n%@\n} catch (e) { console.error('[SkipIntro] failed to start', e); }\n", prefix, source];
    WKUserScript *script = [[WKUserScript alloc] initWithSource:wrapped
                                                  injectionTime:WKUserScriptInjectionTimeAtDocumentEnd
                                               forMainFrameOnly:YES];
    [ucc addUserScript:script];
    if (debug) {
        [ucc addScriptMessageHandler:[SIMessageHandler new] name:@"skipintro"];
        SIAppendLog([NSString stringWithFormat:@"injector: plugin (%lu bytes) added to web view", (unsigned long)source.length]);
    }
}

// Developer mode: evaluate eval.js in the page and write the result to eval.out.
static void SIStartDevEval(void) {
    static dispatch_source_t timer;
    if (timer) return;
    timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
    dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC), NSEC_PER_SEC / 2, NSEC_PER_SEC / 10);
    dispatch_source_set_event_handler(timer, ^{
        NSString *inPath = [SIDir() stringByAppendingPathComponent:@"eval.js"];
        NSString *outPath = [SIDir() stringByAppendingPathComponent:@"eval.out"];
        NSString *js = [NSString stringWithContentsOfFile:inPath encoding:NSUTF8StringEncoding error:nil];
        WKWebView *wv = gWebView;
        if (!js || !wv) return;
        [[NSFileManager defaultManager] removeItemAtPath:inPath error:nil];
        void (^done)(id, NSError *) = ^(id result, NSError *error) {
            NSString *text = error ? [NSString stringWithFormat:@"ERROR: %@", error.localizedDescription]
                                   : [NSString stringWithFormat:@"%@", result ?: @"(null)"];
            [text writeToFile:outPath atomically:YES encoding:NSUTF8StringEncoding error:nil];
        };
        if (@available(macOS 11.0, *)) {
            [wv callAsyncJavaScript:js arguments:@{} inFrame:nil inContentWorld:WKContentWorld.pageWorld completionHandler:done];
        } else {
            [wv evaluateJavaScript:js completionHandler:done];
        }
    });
    dispatch_resume(timer);
}

static IMP gOrigInit = NULL;

static id SIInitWithFrame(id self, SEL _cmd, CGRect frame, WKWebViewConfiguration *config) {
    @try { SIInstallInto(config); } @catch (NSException *e) { NSLog(@"[SkipIntro] install failed: %@", e); }
    id webView = ((id (*)(id, SEL, CGRect, WKWebViewConfiguration *))gOrigInit)(self, _cmd, frame, config);
    if (webView) {
        gWebView = webView;
        if (SIEnvFlag("SKIPINTRO_DEV")) {
            if (@available(macOS 13.3, *)) { ((WKWebView *)webView).inspectable = YES; }
            SIStartDevEval();
        }
    }
    return webView;
}

__attribute__((constructor))
static void SIInit(void) {
    // Only act inside Stremio, even if the variable leaks to child processes.
    NSString *bundleId = [[NSBundle mainBundle] bundleIdentifier];
    if (![bundleId hasPrefix:@"com.westbridge.stremio"]) return;
    Method m = class_getInstanceMethod([WKWebView class], @selector(initWithFrame:configuration:));
    if (!m) return;
    gOrigInit = method_setImplementation(m, (IMP)SIInitWithFrame);
    // Do not pass the injection on to processes Stremio starts (its streaming server, ffmpeg).
    unsetenv("DYLD_INSERT_LIBRARIES");
}
