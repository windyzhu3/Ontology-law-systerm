package io.github.windyzhu3.ontologylaw.opportunity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Pure rendering of a frozen quote. Authorization and issuance belong to their command boundaries. */
public final class QuoteDocumentRenderer {
    public static final String CONTENT_TYPE="text/html; charset=UTF-8";
    private static final DateTimeFormatter DEADLINE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS",Locale.ROOT)
        .withZone(ZoneId.of("Asia/Shanghai"));
    private QuoteDocumentRenderer() {}

    public static byte[] render(QuotePackage pack, UUID tenantId, UUID opportunityId, UUID customerConfirmationId,
                                UUID quoteId, long revision, UUID predecessorId, String customerName) {
        Objects.requireNonNull(pack);Objects.requireNonNull(quoteId);
        String customer=QuotePackage.text(customerName,4000);
        String digest=HexFormat.of().formatHex(pack.digest(tenantId,opportunityId,customerConfirmationId,revision,predecessorId));
        StringBuilder html=new StringBuilder("""
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>法律服务报价单</title>
            <style>
            body{font-family:system-ui,sans-serif;max-width:900px;margin:40px auto;padding:0 24px;color:#18252f;line-height:1.6}
            h1{font-size:26px}h2{font-size:18px;margin-top:28px}p,td,dd{white-space:pre-wrap;overflow-wrap:anywhere}
            table{width:100%;border-collapse:collapse}th,td{border-bottom:1px solid #cbd1d5;padding:10px;text-align:left}
            .amount{text-align:right;font-variant-numeric:tabular-nums}dt{font-weight:bold}dd{margin:0 0 10px}
            .notice{border:1px solid #cbd1d5;padding:14px}.trace{font-size:12px}
            @media print{body{max-width:none;margin:0}tr{break-inside:avoid}h2{break-after:avoid}}
            </style></head><body><main><h1>法律服务报价单</h1>
            """);
        html.append("<p>报价版本：第 ").append(revision).append(" 版</p>");
        field(html,"客户名称",customer);
        html.append("<p class=\"notice\">本文件仅载明本版报价内容，不构成合同或签署证明。下载不代表已交付，也不代表客户已接受或已付款。</p>");
        html.append("<h2>服务范围</h2><p>").append(escape(pack.scope())).append("</p>");
        html.append("<h2>固定费用明细（CNY / 人民币元）</h2><table><thead><tr><th>项目</th><th>类型</th><th class=\"amount\">金额</th></tr></thead><tbody>");
        for(var line:pack.lines()) html.append("<tr><td>").append(escape(line.description())).append("</td><td>")
            .append(line.discount()?"折扣":"服务费").append("</td><td class=\"amount\">").append(money(line.amountMinor())).append("</td></tr>");
        html.append("</tbody><tfoot><tr><th colspan=\"2\">固定总额（CNY）</th><td class=\"amount\">")
            .append(money(pack.totalMinor())).append("</td></tr></tfoot></table>");
        html.append("<h2>条件性费用</h2><p>条件性费用不计入固定总额。</p>");
        if(pack.conditionalFee()==null) html.append("<p>无条件性费用</p>");
        else {
            var fee=pack.conditionalFee();field(html,"计算依据及触发条件",fee.basis());
            field(html,"费率",BigDecimal.valueOf(fee.rateBasisPoints(),2).toPlainString()+"%（"+fee.rateBasisPoints()+" 基点）");
            field(html,"条件性费用上限（CNY）",money(fee.capMinor()));
        }
        html.append("<h2>付款安排</h2><p>").append(escape(pack.paymentTerms())).append("</p>");
        field(html,"有效截止时间",DEADLINE.format(pack.validUntil())+"（Asia/Shanghai，UTC+08:00）");
        field(html,"准确截止时间（UTC）",pack.validUntil().toString());
        html.append("<section class=\"trace\"><h2>准确版本追溯</h2><dl>");
        trace(html,"报价编号",quoteId.toString());trace(html,"租户编号",tenantId.toString());
        trace(html,"商机编号",opportunityId.toString());trace(html,"客户需求确认版本",customerConfirmationId.toString());
        trace(html,"直接前版",predecessorId==null?"无（首版）":predecessorId.toString());
        trace(html,"报价内容协议",QuotePackage.CONTRACT);trace(html,"报价内容摘要（SHA-256）",digest);
        html.append("</dl></section></main></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String filename(UUID quoteId,long revision) {
        Objects.requireNonNull(quoteId);
        if(revision<1 || revision>9007199254740991L)throw new IllegalArgumentException("Exact quote revision required");
        return "quote-"+quoteId+"-v"+revision+".html";
    }
    private static String money(long minor) {
        return new DecimalFormat("#,##0.00",DecimalFormatSymbols.getInstance(Locale.ROOT)).format(BigDecimal.valueOf(minor,2));
    }
    private static void field(StringBuilder html,String label,String value) {
        html.append("<p><strong>").append(escape(label)).append("：</strong>").append(escape(value)).append("</p>");
    }
    private static void trace(StringBuilder html,String label,String value) {
        html.append("<dt>").append(escape(label)).append("</dt><dd>").append(escape(value)).append("</dd>");
    }
    private static String escape(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("'","&#39;");
    }
}
