/* crashwatch.c —— 崩溃现场记录器（LD_PRELOAD 注入）
 *
 * 为什么需要它：NapCat 的 worker 子进程在真机上以「退出码 11」反复退出。
 * 已在模拟器上用 kill -SEGV 复现确认：退出码 11 就是 SIGSEGV。
 * 日志里没有任何 C++ 层面的报错，proot 里没有 root、没有 debuggerd、
 * Chromium 的 Crashpad 也写不出 dump（只剩一个 client_id），
 * 所以只能自己在信号处理函数里把现场写下来：信号号、si_code、故障地址、
 * pc / lr / sp、栈顶 512B，以及完整的 /proc/self/maps。
 *
 * ★ v2 的关键改动：拦 sigaction()
 *   v1 的 constructor 里装好处理器，但 Chromium 启动时会用 sigaction()
 *   把自己的处理器装上去，直接把我们的顶掉 —— 所以真机崩溃时 crashes/ 一直是空的。
 *   v2 导出自己的 sigaction()：应用（Chromium / Node）来装处理器时，
 *   我们把它记在 g_app[] 里，但实际装的是我们的处理器；崩溃时先写现场，
 *   再把控制权交还给应用原本要装的那个处理器，保证它原有的行为不变。
 *
 * 设计约束（信号处理函数里只能干异步信号安全的事）：
 *   - 不用 stdio / malloc / printf，全部 open/write + 手写十六进制；
 *   - sigaltstack，防止栈溢出时处理函数自己没栈可用；
 *   - backtrace() 不是异步信号安全的，放最后做，先 alarm(3) 兜底；
 *   - 写完现场按原信号重新 raise，退出码与没装本库时一致。
 *
 * 编译（沙箱内，g++ 由 build-essential 提供）：
 *   g++ -shared -fPIC -O2 -o crashwatch.so crashwatch.c -ldl
 *
 * 用法（run-napcat.sh 里）：
 *   QQBOT_CRASH_DIR=... LD_PRELOAD=/path/crashwatch.so qq --no-sandbox
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <ucontext.h>
#include <execinfo.h>
#include <sys/syscall.h>
#include <sys/types.h>

static volatile sig_atomic_t g_in_handler = 0;
static char g_dir[200] = "";
static char g_tag[64] = "";

/* ---------- 极简格式化（只依赖栈，不碰堆） ---------- */

static int put_str(char *b, int n, const char *s) {
    int i = 0;
    while (s && s[i] && n - i - 1 > 0) { b[i] = s[i]; i++; }
    b[i] = 0;
    return i;
}

static int put_hex(char *b, int n, unsigned long v) {
    static const char *hx = "0123456789abcdef";
    char t[18];
    int k = 0, i = 0;
    if (v == 0) { t[k++] = '0'; }
    while (v) { t[k++] = hx[v & 0xf]; v >>= 4; }
    if (n < k + 3) return 0;
    b[i++] = '0'; b[i++] = 'x';
    while (k) b[i++] = t[--k];
    b[i] = 0;
    return i;
}

static int put_dec(char *b, int n, long v) {
    char t[24];
    int k = 0, i = 0;
    unsigned long u;
    if (v < 0) { if (n > 1) { b[i++] = '-'; } u = (unsigned long)(-v); }
    else u = (unsigned long)v;
    if (u == 0) t[k++] = '0';
    while (u) { t[k++] = (char)('0' + (u % 10)); u /= 10; }
    if (n < i + k + 1) return 0;
    while (k) b[i++] = t[--k];
    b[i] = 0;
    return i;
}

static void wcat(int fd, const char *s) {
    size_t n = strlen(s);
    ssize_t r;
    while (n > 0) {
        r = write(fd, s, n);
        if (r <= 0) return;
        s += r; n -= (size_t)r;
    }
}

static void wdec(int fd, long v) {
    char b[32];
    put_dec(b, (int)sizeof(b), v);
    wcat(fd, b);
}

static void whex(int fd, unsigned long v) {
    char b[24];
    put_hex(b, (int)sizeof(b), v);
    wcat(fd, b);
}

static void wname(int fd, const char *label, unsigned long v) {
    wcat(fd, label); wcat(fd, " = "); whex(fd, v); wcat(fd, "\n");
}

/* ---------- 真正的 sigaction / sigaltstack ---------- */

typedef int (*sa_fn)(int, const struct sigaction *, struct sigaction *);
static sa_fn g_real_sa = 0;

static sa_fn real_sa(void) {
    if (!g_real_sa) {
        g_real_sa = (sa_fn)dlsym(RTLD_NEXT, "sigaction");
        if (!g_real_sa) g_real_sa = (sa_fn)dlsym(RTLD_DEFAULT, "sigaction");
    }
    return g_real_sa;
}

/* 我们盯的信号，以及应用原本要装的处理器 */
static const int g_watch[] = { SIGSEGV, SIGBUS, SIGABRT, SIGILL, SIGFPE, SIGTRAP };
#define NWATCH ((int)(sizeof(g_watch) / sizeof(g_watch[0])))
static struct sigaction g_app[NSIG];
static volatile sig_atomic_t g_app_ok[NSIG];

static int is_watched(int sig) {
    int i;
    for (i = 0; i < NWATCH; i++) if (g_watch[i] == sig) return 1;
    return 0;
}

static void handler(int sig, siginfo_t *si, void *uctx);

/* 导出给应用看的 sigaction：记账 + 换成我们的 */
int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    sa_fn real = real_sa();
    if (!real) return -1;
    if (act && sig > 0 && sig < NSIG && is_watched(sig)) {
        g_app[sig] = *act;
        g_app_ok[sig] = 1;
        {
            struct sigaction mine;
            memset(&mine, 0, sizeof(mine));
            mine.sa_sigaction = handler;
            mine.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
            sigemptyset(&mine.sa_mask);
            return real(sig, &mine, old);
        }
    }
    return real(sig, act, old);
}

/* ---------- 把 pc 落在哪一段映射里找出来（纯栈上解析，不 malloc） ---------- */

static unsigned long parse_hex(const char *p, const char **endp) {
    unsigned long v = 0;
    int any = 0;
    while (*p == ' ') p++;
    for (;;) {
        char c = *p;
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else break;
        v = (v << 4) | (unsigned long)d;
        any = 1; p++;
    }
    if (endp) *endp = p;
    return any ? v : 0;
}

static void dump_pc_segment(int fd, unsigned long pc) {
    int mf = open("/proc/self/maps", O_RDONLY);
    char buf[4096];
    char line[512];
    ssize_t r;
    int ll = 0;
    int found = 0;

    if (mf < 0) return;
    while (!found && (r = read(mf, buf, sizeof(buf))) > 0) {
        ssize_t i;
        for (i = 0; i < r; i++) {
            char c = buf[i];
            if (c == '\n') {
                const char *e = 0;
                unsigned long lo, hi;
                line[ll] = 0;
                lo = parse_hex(line, &e);
                if (e && *e == '-') hi = parse_hex(e + 1, &e);
                else { hi = 0; }
                if (pc >= lo && pc < hi) {
                    wcat(fd, "--- pc 所在段 ---\n");
                    wcat(fd, line);
                    wcat(fd, "\n");
                    found = 1;
                }
                ll = 0;
                if (found) break;
            } else if (ll < (int)sizeof(line) - 1) {
                line[ll++] = c;
            }
        }
    }
    close(mf);
}

/* ---------- 信号处理 ---------- */

static void chain_to_app(int sig) {
    if (g_app_ok[sig]) {
        struct sigaction *a = &g_app[sig];
        sa_fn real = real_sa();
        if (a->sa_handler == SIG_IGN) return;
        if (a->sa_handler != SIG_DFL) {
            if (real) real(sig, a, 0);
            if (a->sa_flags & SA_SIGINFO) {
                if (a->sa_sigaction) a->sa_sigaction(sig, 0, 0);
            } else {
                if (a->sa_handler) a->sa_handler(sig);
            }
            return;
        }
    }
    {
        sa_fn real = real_sa();
        struct sigaction d;
        memset(&d, 0, sizeof(d));
        d.sa_handler = SIG_DFL;
        if (real) real(sig, &d, 0);
        else signal(sig, SIG_DFL);
    }
    raise(sig);
}

static void handler(int sig, siginfo_t *si, void *uctx) {
    int fd;
    char path[320];
    int plen = 0;

    if (g_in_handler) {
        /* 处理过程里又崩了：别递归，交给应用原来的处理器 / 默认动作 */
        g_in_handler = 0;
        chain_to_app(sig);
        return;
    }
    g_in_handler = 1;

    alarm(3);

    plen = put_str(path, (int)sizeof(path), g_dir);
    if (plen > 0 && path[plen - 1] != '/') { path[plen++] = '/'; }
    {
        char tail[96];
        int t = 0;
        t = put_str(tail, (int)sizeof(tail), "crash-");
        t += put_dec(tail + t, (int)sizeof(tail) - t, (long)syscall(SYS_gettid));
        t += put_str(tail + t, (int)sizeof(tail) - t, "-sig");
        t += put_dec(tail + t, (int)sizeof(tail) - t, (long)sig);
        t += put_str(tail + t, (int)sizeof(tail) - t, ".log");
        tail[t] = 0;
        if (plen + t + 1 < (int)sizeof(path)) {
            memcpy(path + plen, tail, (size_t)t + 1);
        } else {
            put_str(path, (int)sizeof(path), "/tmp/crashwatch.log");
        }
    }

    fd = open(path, O_CREAT | O_WRONLY | O_APPEND, 0600);
    if (fd < 0) {
        fd = open("/tmp/crashwatch.log", O_CREAT | O_WRONLY | O_APPEND, 0600);
    }
    if (fd >= 0) {
        wcat(fd, "===== crashwatch =====\n");
        wcat(fd, "tag      = "); wcat(fd, g_tag); wcat(fd, "\n");
        wcat(fd, "signal   = "); wdec(fd, sig); wcat(fd, " (");
        switch (sig) {
            case SIGSEGV: wcat(fd, "SIGSEGV"); break;
            case SIGBUS:  wcat(fd, "SIGBUS");  break;
            case SIGABRT: wcat(fd, "SIGABRT"); break;
            case SIGILL:  wcat(fd, "SIGILL");  break;
            case SIGFPE:  wcat(fd, "SIGFPE");  break;
            case SIGTRAP: wcat(fd, "SIGTRAP"); break;
            default:      wcat(fd, "other");   break;
        }
        wcat(fd, ")\n");
        wcat(fd, "pid      = "); wdec(fd, (long)getpid());  wcat(fd, "\n");
        wcat(fd, "tid      = "); wdec(fd, (long)syscall(SYS_gettid)); wcat(fd, "\n");
        wcat(fd, "app_hdlr = "); wdec(fd, (long)(g_app_ok[sig] ? 1 : 0)); wcat(fd, "\n");
        if (si) {
            wcat(fd, "si_code  = "); wdec(fd, (long)si->si_code); wcat(fd, "\n");
            wname(fd, "si_addr", (unsigned long)si->si_addr);
        }
        {
            unsigned long pcv = 0, spv = 0;
            if (uctx) {
                ucontext_t *u = (ucontext_t *)uctx;
#if defined(__aarch64__)
                const mcontext_t *m = &u->uc_mcontext;
                pcv = (unsigned long)m->pc;
                spv = (unsigned long)m->sp;
                wname(fd, "pc", pcv);
                wname(fd, "lr", (unsigned long)m->regs[30]);
                wname(fd, "sp", spv);
                wname(fd, "fp", (unsigned long)m->regs[29]);
                wname(fd, "x0", (unsigned long)m->regs[0]);
                wname(fd, "x1", (unsigned long)m->regs[1]);
#elif defined(__x86_64__)
                const greg_t *g = u->uc_mcontext.gregs;
                pcv = (unsigned long)g[REG_RIP];
                spv = (unsigned long)g[REG_RSP];
                wname(fd, "rip", pcv);
                wname(fd, "rsp", spv);
                wname(fd, "rbp", (unsigned long)g[REG_RBP]);
#else
                pcv = (unsigned long)u->uc_mcontext.pc;
                wname(fd, "pc", pcv);
#endif
            }
            if (pcv) dump_pc_segment(fd, pcv);
            if (spv) {
                unsigned long base = spv & ~0xFUL;
                wcat(fd, "--- stack 512B ---\n");
                {
                    const unsigned long *p = (const unsigned long *)base;
                    int i;
                    char line[128];
                    for (i = 0; i < 32; i++) {
                        int o = 0;
                        o = put_hex(line, (int)sizeof(line), (unsigned long)(base + (unsigned long)i * 8));
                        o += put_str(line + o, (int)sizeof(line) - o, ": ");
                        o += put_hex(line + o, (int)sizeof(line) - o, p[i]);
                        line[o++] = '\n'; line[o] = 0;
                        wcat(fd, line);
                    }
                }
            }
        }
        {
            int mf = open("/proc/self/maps", O_RDONLY);
            if (mf >= 0) {
                char buf[4096];
                ssize_t r;
                wcat(fd, "--- /proc/self/maps ---\n");
                while ((r = read(mf, buf, sizeof(buf))) > 0) {
                    ssize_t off = 0;
                    while (off < r) {
                        ssize_t k = write(fd, buf + off, (size_t)(r - off));
                        if (k <= 0) break;
                        off += k;
                    }
                }
                close(mf);
            }
        }
        wcat(fd, "--- backtrace ---\n");
        {
            void *bt[48];
            int n = backtrace(bt, (int)(sizeof(bt) / sizeof(bt[0])));
            if (n > 0) backtrace_symbols_fd(bt, n, fd);
        }
        close(fd);
    }

    g_in_handler = 0;
    chain_to_app(sig);
}

__attribute__((constructor)) static void crashwatch_init(void) {
    const char *d = getenv("QQBOT_CRASH_DIR");
    const char *t = getenv("QQBOT_CRASH_TAG");
    struct sigaction sa;
    sa_fn real = real_sa();
    /* 不能用 SIGSTKSZ：glibc 2.34 起它是 sysconf(_SC_SIGSTKSZ)，不是编译期常量 */
    static char altstk[65536];
    stack_t ss;
    int i;

    if (d && *d) put_str(g_dir, (int)sizeof(g_dir), d);
    else put_str(g_dir, (int)sizeof(g_dir), "/tmp");
    if (t && *t) put_str(g_tag, (int)sizeof(g_tag), t);
    else put_str(g_tag, (int)sizeof(g_tag), "qq");

    ss.ss_sp = altstk;
    ss.ss_size = sizeof(altstk);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    for (i = 0; i < NWATCH; i++) {
        int s = g_watch[i];
        /* 走真正的 sigaction，别走我们自己的（那会把 g_app 记成我们自己的 handler）。
         * real 理论上一定拿得到；万一没有就干脆不装（只是少了诊断，不影响 QQ）。 */
        if (real) real(s, &sa, NULL);
    }
}
