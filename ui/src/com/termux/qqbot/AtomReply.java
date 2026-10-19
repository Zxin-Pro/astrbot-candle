package com.termux.app;

/**
 * AtomNet 一次请求的结果。
 *
 * 为什么不是一行 JSON 字符串：两个上报口（POST）只需要 ok/code/err，
 * 但公告是 GET，还得拿响应正文（公告内容本身）和 ETag（下次 If-None-Match 用），
 * 304 还要能一眼认出来。把这些塞进同一行 JSON 再二次解析纯属自找麻烦。
 *
 * 仍然是顶层类、无内部类（d8 处理匿名/内部类会崩）。
 */
final class AtomReply {

    /** true = 2xx。 */
    final boolean ok;

    /** HTTP 状态码；0 表示根本没连上（DNS / 超时 / 证书 / 断网）。 */
    final int code;

    /** 2xx 时的响应正文（已按上限截断）；非 2xx 一律空串。 */
    final String body;

    /** 响应头 ETag；没有则空串。 */
    final String etag;

    /** ok=false 时的原因：异常原文，或一小段响应体。 */
    final String err;

    AtomReply(boolean ok, int code, String body, String etag, String err) {
        this.ok = ok;
        this.code = code;
        this.body = (body == null) ? "" : body;
        this.etag = (etag == null) ? "" : etag;
        this.err = (err == null) ? "" : err;
    }

    /** 304：内容没变，调用方留着旧数据、只把时间戳更新一下。 */
    boolean notModified() {
        return code == 304;
    }

    /** 与上报口完全同形的 {"ok","code","err"}，POST 调用方直接拿去回桥。 */
    String json() {
        return AtomNet.result(ok, code, err);
    }
}
