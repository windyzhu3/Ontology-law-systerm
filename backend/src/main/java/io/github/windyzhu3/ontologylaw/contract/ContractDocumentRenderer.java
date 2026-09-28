package io.github.windyzhu3.ontologylaw.contract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.form.*;

/** Fills a versioned approved PDF; no clauses are invented and no business facts are written. */
public final class ContractDocumentRenderer {
    public static final Set<String> FIELDS=Set.of("customer","parties","scope","fees","payment","signing","transfer","basis");
    private static final Pattern FONT=Pattern.compile("/([^\\s/]+)\\s+([0-9]+(?:\\.[0-9]+)?)\\s+Tf");
    private ContractDocumentRenderer() {}
    public static byte[] render(byte[] template,Map<String,String> fields){
        if(template==null||template.length==0||template.length>20*1024*1024||fields==null||!fields.keySet().equals(FIELDS))throw invalid();
        try(var pdf=Loader.loadPDF(template);var out=new ByteArrayOutputStream()){
            var catalog=pdf.getDocumentCatalog();var form=catalog.getAcroForm();
            if(pdf.isEncrypted()||pdf.getNumberOfPages()<1||pdf.getNumberOfPages()>200||form==null||form.hasXFA()||catalog.getOpenAction()!=null||catalog.getCOSObject().containsKey(COSName.AA))throw invalid();
            if(catalog.getNames()!=null&&(catalog.getNames().getJavaScript()!=null||catalog.getNames().getEmbeddedFiles()!=null))throw invalid();
            var names=new HashSet<String>();
            for(var raw:form.getFieldTree()){
                if(!(raw instanceof PDTextField field)||!names.add(field.getFullyQualifiedName())||!FIELDS.contains(field.getFullyQualifiedName())||field.getWidgets().size()!=1||field.getCOSObject().containsKey(COSName.AA)||field.isRichText()||field.isPassword())throw invalid();
                String value=fields.get(field.getFullyQualifiedName());if(value==null||value.isBlank()||value.length()>40000||value.codePoints().anyMatch(c->c<32&&c!=10&&c!=13||c>=127&&c<=159))throw invalid();
                var widget=field.getWidgets().getFirst();var rect=widget.getRectangle();if(rect==null||rect.getWidth()<10||rect.getHeight()<10||widget.getAction()!=null||widget.getCOSObject().containsKey(COSName.AA))throw invalid();
                var page=widget.getPage();
                if(widget.isHidden()||widget.isInvisible()||widget.isNoView()||page==null||page.getRotation()!=0||!java.util.stream.StreamSupport.stream(pdf.getPages().spliterator(),false).anyMatch(p->p.getCOSObject()==page.getCOSObject())||page.getAnnotations().stream().noneMatch(a->a.getCOSObject()==widget.getCOSObject()))throw invalid();
                var crop=page.getCropBox();
                if(!Float.isFinite(rect.getLowerLeftX())||!Float.isFinite(rect.getLowerLeftY())||!Float.isFinite(rect.getUpperRightX())||!Float.isFinite(rect.getUpperRightY())||rect.getLowerLeftX()<crop.getLowerLeftX()||rect.getLowerLeftY()<crop.getLowerLeftY()||rect.getUpperRightX()>crop.getUpperRightX()||rect.getUpperRightY()>crop.getUpperRightY())throw invalid();
                String appearance=field.getDefaultAppearance();var match=FONT.matcher(appearance==null?"":appearance);if(!match.find()||form.getDefaultResources()==null)throw invalid();
                float size=Float.parseFloat(match.group(2));if(!Float.isFinite(size)||size<8||size>48)throw invalid();var font=form.getDefaultResources().getFont(COSName.getPDFName(match.group(1)));if(font==null)throw invalid();
                String fitted=fit(value.replace("\r\n","\n").replace('\r','\n'),font,size,rect.getWidth()-8,rect.getHeight()-8,field.isMultiline());
                if(field.getMaxLen()>0&&fitted.length()>field.getMaxLen())throw invalid();field.setReadOnly(false);field.setValue(fitted);
            }
            if(!names.equals(FIELDS))throw invalid();form.setNeedAppearances(false);form.flatten();
            // PDFBox otherwise creates a time-dependent document id. Keep exact retry bytes stable.
            var id=new COSArray();byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((Base64.getEncoder().encodeToString(template)+new TreeMap<>(fields)).getBytes(StandardCharsets.UTF_8));id.add(new COSString(digest));id.add(new COSString(digest));pdf.getDocument().setDocumentID(id);
            pdf.save(out);if(out.size()>20*1024*1024)throw invalid();return out.toByteArray();
        }catch(IOException|java.security.NoSuchAlgorithmException|RuntimeException failure){if(failure instanceof IllegalArgumentException e && e.getMessage().equals("Contract template cannot render complete content"))throw e;throw invalid();}
    }
    private static String fit(String text,PDFont font,float size,float width,float height,boolean multiline)throws IOException {
        var lines=new ArrayList<String>();var current=new StringBuilder();float used=0;
        for(int cp:text.codePoints().toArray()){
            if(cp=='\n'){if(!multiline)throw invalid();lines.add(current.toString());current.setLength(0);used=0;continue;}
            String ch=new String(Character.toChars(cp));float advance=font.getStringWidth(ch)*size/1000;if(advance>width)throw invalid();
            if(used+advance>width){if(!multiline)throw invalid();lines.add(current.toString());current.setLength(0);used=0;}
            current.append(ch);used+=advance;
        }
        lines.add(current.toString());if(lines.size()*size*1.3f>height)throw invalid();return String.join("\n",lines);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Contract template cannot render complete content");}
}
