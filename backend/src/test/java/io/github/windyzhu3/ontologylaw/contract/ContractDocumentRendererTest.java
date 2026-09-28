package io.github.windyzhu3.ontologylaw.contract;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractDocumentRendererTest {
    static final List<String> KEYS=List.of("customer","parties","scope","fees","payment","signing","transfer","basis");
    static Map<String,String> values(){var v=new LinkedHashMap<String,String>();KEYS.forEach(k->v.put(k,k+" exact value"));v.put("fees","Fixed CNY 20000.01\nDiscount CNY -100.00\nConditional 5.25% cap 999.99");return v;}
    static byte[] template(boolean omit)throws IOException {return template(omit,null);}
    static byte[] template(boolean omit,String fontPath)throws IOException {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage(PDRectangle.A4);pdf.addPage(page);
            try(var content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(30,800);content.showText("APPROVED ORIGINAL CLAUSES - SYNTHETIC ONLY");content.endText();}
            var form=new PDAcroForm(pdf);pdf.getDocumentCatalog().setAcroForm(form);var resources=new PDResources();
            PDFont font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);if(fontPath!=null)try(var file=new FileInputStream(fontPath)){font=PDType0Font.load(pdf,file,false);}
            resources.put(COSName.getPDFName("Helv"),font);form.setDefaultResources(resources);form.setDefaultAppearance("/Helv 10 Tf 0 g");
            int i=0;for(var key:KEYS){if(omit&&key.equals("basis"))continue;var field=new PDTextField(form);field.setPartialName(key);field.setMultiline(true);field.setDefaultAppearance("/Helv 10 Tf 0 g");form.getFields().add(field);var widget=field.getWidgets().getFirst();widget.setRectangle(new PDRectangle(30,710-i++*85,530,75));widget.setPage(page);page.getAnnotations().add(widget);}
            pdf.save(out);return out.toByteArray();
        }
    }
    @Test void renders_complete_template_and_exact_values_as_flattened_repeatable_pdf()throws Exception {
        var template=template(false);var a=ContractDocumentRenderer.render(template,values());var b=ContractDocumentRenderer.render(template,values());assertArrayEquals(a,b);
        try(var pdf=Loader.loadPDF(a)){String text=new PDFTextStripper().getText(pdf);assertTrue(text.contains("APPROVED ORIGINAL CLAUSES"));assertTrue(text.contains("20000.01"));assertTrue(text.contains("-100.00"));assertTrue(text.contains("5.25%"));assertTrue(text.contains("999.99"));assertTrue(pdf.getDocumentCatalog().getAcroForm()==null||pdf.getDocumentCatalog().getAcroForm().getFields().isEmpty());}
    }
    @Test void missing_template_field_or_unknown_input_is_rejected()throws Exception {
        assertThrows(IllegalArgumentException.class,()->ContractDocumentRenderer.render(template(true),values()));var v=values();v.put("unexpected","secret");assertThrows(IllegalArgumentException.class,()->ContractDocumentRenderer.render(template(false),v));
    }
    @Test void unsupported_chinese_font_and_overflow_fail_without_partial_document()throws Exception {
        var chinese=values();chinese.put("scope","中文合同范围");assertThrows(IllegalArgumentException.class,()->ContractDocumentRenderer.render(template(false),chinese));var longText=values();longText.put("scope","x".repeat(10000));assertThrows(IllegalArgumentException.class,()->ContractDocumentRenderer.render(template(false),longText));
    }
    @Test void invisible_or_unplaced_required_fields_are_rejected()throws Exception {
        for(int mode=0;mode<5;mode++)try(var pdf=Loader.loadPDF(template(false));var out=new ByteArrayOutputStream()){
            var widget=pdf.getDocumentCatalog().getAcroForm().getField("fees").getWidgets().getFirst();
            switch(mode){case 0->widget.setHidden(true);case 1->widget.setInvisible(true);case 2->widget.setRectangle(new PDRectangle(900,900,530,75));case 3->pdf.getPage(0).getAnnotations().clear();case 4->widget.setPage(new PDPage());}
            pdf.save(out);assertThrows(IllegalArgumentException.class,()->ContractDocumentRenderer.render(out.toByteArray(),values()),"mode="+mode);
        }
    }
    @Test void complete_embedded_chinese_font_preserves_chinese_text()throws Exception {
        String path=System.getProperty("ols.contract.testFont");org.junit.jupiter.api.Assumptions.assumeTrue(path!=null,"Explicit licensed local CJK font required");
        var data=template(false,path);var v=values();v.put("scope","中文服务范围：审阅采购协议，不包含案件分类。");v.put("customer","合成客户有限公司");var generated=ContractDocumentRenderer.render(data,v);
        try(var pdf=Loader.loadPDF(generated)){String text=new PDFTextStripper().getText(pdf);assertTrue(text.contains("合成客户有限公司"));assertTrue(text.contains("中文服务范围"));}
        String output=System.getProperty("ols.contract.fixtureDirectory");if(output!=null){var root=java.nio.file.Path.of(output);java.nio.file.Files.createDirectories(root);java.nio.file.Files.write(root.resolve("synthetic-fillable-template.pdf"),data);java.nio.file.Files.write(root.resolve("synthetic-generated-contract.pdf"),generated);}
    }
}
