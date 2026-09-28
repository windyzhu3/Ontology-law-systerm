package io.github.windyzhu3.ontologylaw.opportunity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteDocumentRendererTest {
    private final UUID tenant=UUID.randomUUID(), opportunity=UUID.randomUUID(), confirmation=UUID.randomUUID(), quote=UUID.randomUUID(), previous=UUID.randomUUID();
    private QuotePackage pack() {
        return new QuotePackage("CNY","代理范围 <限诉前> & 沟通",List.of(
            new QuotePackage.Line("服务 <A>",123456,false),new QuotePackage.Line("折扣",-3456,true)),
            "首期 50%\n余款另行支付",Instant.parse("2026-09-20T20:30:01.123456Z"),
            new QuotePackage.ConditionalFee("实际回款 & 已到账",125,9007199254740991L));
    }
    private byte[] render(QuotePackage p,String name) {
        return QuoteDocumentRenderer.render(p,tenant,opportunity,confirmation,quote,2,previous,name);
    }
    @Test void contains_frozen_terms_exact_amounts_and_traceable_version() {
        var p=pack();String html=new String(render(p,"张三公司"),StandardCharsets.UTF_8);
        for(String expected:List.of("张三公司","第 2 版","服务 &lt;A&gt;","1,234.56","-34.56","1,200.00",
            "实际回款 &amp; 已到账","1.25%","125 基点","90,071,992,547,409.91","首期 50%\n余款另行支付",
            "2026-09-21 04:30:01.123456","Asia/Shanghai",p.validUntil().toString(),
            quote.toString(),confirmation.toString(),previous.toString(),
            HexFormat.of().formatHex(p.digest(tenant,opportunity,confirmation,2,previous)),
            "不计入固定总额","不构成合同或签署证明","下载不代表已交付")) assertTrue(html.contains(expected),expected);
    }
    @Test void user_text_cannot_create_markup_or_active_content() {
        var p=new QuotePackage("CNY","<script>alert('x')</script>",List.of(new QuotePackage.Line("<img src=//x>",0,false)),
            "<iframe src='https://x'>&\"",pack().validUntil(),new QuotePackage.ConditionalFee("<svg onload=alert(1)>",1,1));
        String html=new String(render(p,"<a href='https://x'>\" & 客户</a>"),StandardCharsets.UTF_8);
        assertFalse(html.contains("<script"));assertFalse(html.contains("<img"));assertFalse(html.contains("<iframe"));assertFalse(html.contains("<svg"));assertFalse(html.contains("<a "));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"));
        assertTrue(html.contains("&quot; &amp; 客户&lt;/a&gt;"));assertTrue(html.contains("charset=\"UTF-8\""));
    }
    @Test void output_is_deterministic_across_server_locale_and_timezone_and_supports_zero() {
        var p=new QuotePackage("CNY","范围",List.of(new QuotePackage.Line("公益服务",0,false)),"无付款",pack().validUntil(),null);
        Locale before=Locale.getDefault();TimeZone zone=TimeZone.getDefault();
        try {
            byte[] original=render(p,"客户");Locale.setDefault(Locale.GERMANY);TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertArrayEquals(original,render(p,"客户"));String html=new String(original,StandardCharsets.UTF_8);
            assertTrue(html.contains("0.00"));assertTrue(html.contains("无条件性费用"));
        } finally {Locale.setDefault(before);TimeZone.setDefault(zone);}
        assertEquals("quote-"+quote+"-v2.html",QuoteDocumentRenderer.filename(quote,2));
        assertEquals("text/html; charset=UTF-8",QuoteDocumentRenderer.CONTENT_TYPE);
    }
}
