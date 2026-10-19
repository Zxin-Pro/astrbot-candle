/* crashwatch.c —— 崩溃现场记录器（LD_PRELOAD 注入）
 *
 * 为什么需要它：NapCat 的 worker 子进程在真机上以「退出码 11」（SIGSEGV）反复退出，
 * 日志里没有任何 C++ 层面的报错，只有一句「Worker进程退出，退出码: 11」。
 * proot 里没有 root、没有 debuggerd、拿不到 tombstone，所以只能自己在信号处理函数里
 * 把现场写下来：信号号、si_code、故障地址、pc / lr / sp，以及完整的 /proc/self/maps。
 * 有了 maps，拿 pc 减去对应段的起始地址就能算出「崩在哪个 .so 的第几个字节」。
 *
 * 设计约束（信号处理函数里只能干异步信号安全的事）：
 *   - 不用 stdio / malloc / printf，全部用 open/write 和手写十六进制格式化；
 *   - 用 sigaltstack，防止栈溢出时处理函数自己没栈可用；
 *   - backtrace() 不是异步信号安全的（内部可能 malloc），所以放在最后做，
 *     并且先 alarm(3) 兜底 —— 万一它死锁，3 秒后被 SIGALRM 干掉，不留僵尸；
 *   - 处理完按原信号重新 raise，退出码保持和没装它时一致，不影响上层判断。
 *
 * 编译（沙箱内，g++ 由 build-essential 提供）：
 *   g++ -shared -fPIC -O2 -o crashwatch.so crashwatch.c
 *
 * 用法（run-napcat.sh 里）：
 *   QQBOT_CRASH_DIR=... LD_PRELOAD=/path/crashwatch.so qq --no-sandbox
 * 子进程会继承 LD_PRELOAD，所以 worker 崩溃也能抓到。
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
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

/* 追加一小段文本到 fd（用栈上的定长缓冲，不 malloc） */
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

/* ---------- 信号处理 ---------- */

static void handler(int sig, siginfo_t *si, void *uctx) {
    int fd;
    char path[320];
    int plen = 0;

    if (g_in_handler) {
        /* 处理过程里又崩了：别递归，直接按默认动作死 */
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    g_in_handler = 1;

    /* 3 秒兜底：后面 backtrace() 万一死锁，会被 SIGALRM 收尸 */
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
        if (si) {
            wcat(fd, "si_code  = "); wdec(fd, (long)si->si_code); wcat(fd, "\n");
            wname(fd, "si_addr", (unsigned long)si->si_addr);
        }
        if (uctx) {
            ucontext_t *u = (ucontext_t *)uctx;
#if defined(__aarch64__)
            const mcontext_t *m = &u->uc_mcontext;
            wname(fd, "pc", (unsigned long)m->pc);
            wname(fd, "lr", (unsigned long)m->regs[30]);
            wname(fd, "sp", (unsigned long)m->sp);
            wname(fd, "fp", (unsigned long)m->regs[29]);
            wname(fd, "x0", (unsigned long)m->regs[0]);
            wname(fd, "x1", (unsigned long)m->regs[1]);
#elif defined(__x86_64__)
            const greg_t *g = u->uc_mcontext.gregs;
            wname(fd, "rip", (unsigned long)g[REG_RIP]);
            wname(fd, "rsp", (unsigned long)g[REG_RSP]);
            wname(fd, "rbp", (unsigned long)g[REG_RBP]);
#else
            wname(fd, "pc", (unsigned long)u->uc_mcontext.pc);
#endif
        }
        /* 栈顶 512 字节：返回地址基本都在里面，配合 maps 可以人工展开 */
        {
            unsigned long spv = 0;
            if (uctx) {
#if defined(__aarch64__)
                spv = (unsigned long)((ucontext_t *)uctx)->uc_mcontext.sp;
#elif defined(__x86_64__)
                spv = (unsigned long)((ucontext_t *)uctx)->uc_mcontext.gregs[REG_RSP];
#endif
            }
            if (spv) {
                unsigned long base = spv & ~0xFUL;
                wcat(fd, "--- stack 512B ---\n");
                {
                    const unsigned long *p = (const unsigned long *)base;
                    int i, ok = 1;
                    char line[128];
                    for (i = 0; i < 32 && ok; i++) {
                        int o = 0;
                        o = put_hex(line, (int)sizeof(line), (unsigned long)(base + (unsigned long)i * 8));
                        o += put_str(line + o, (int)sizeof(line) - o, ": ");
                        o += put_hex(line + o, (int)sizeof(line) - o, p[i]);
                        line[o++] = '\n'; line[o] = 0;
                        wcat(fd, line);
                    }
                    (void)ok;
                }
            }
        }
        /* 内存映射：pc 落在哪一段、该段从哪个文件加载，全靠它 */
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
        /* 最后才试符号化调用栈（内部可能 malloc，已用 alarm(3) 兜底） */
        wcat(fd, "--- backtrace ---\n");
        {
            void *bt[48];
            int n = backtrace(bt, (int)(sizeof(bt) / sizeof(bt[0])));
            if (n > 0) backtrace_symbols_fd(bt, n, fd);
        }
        close(fd);
    }

    /* 按原信号重新抛出，退出码与没装本库时一致 */
    signal(sig, SIG_DFL);
    raise(sig);
}

__attribute__((constructor)) static void crashwatch_init(void) {
    const char *d = getenv("QQBOT_CRASH_DIR");
    const char *t = getenv("QQBOT_CRASH_TAG");
    struct sigaction sa;
    /* 不能用 SIGSTKSZ：glibc 2.34 起它是 sysconf(_SC_SIGSTKSZ)，不是编译期常量，
     * 拿来定义静态数组会报 "storage size isn't constant"。固定 64 KB 足够，
     * 处理函数本身只用栈上的小缓冲，不递归。 */
    static char altstk[65536];
    stack_t ss;

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
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGTRAP, &sa, NULL);
}
