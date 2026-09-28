package io.github.windyzhu3.ontologylaw.api;
import java.io.*;import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
/** Keep the one-file ingress streaming: the store owns actual-byte bounds, not an eager Resource buffer. */
@Configuration(proxyBeanMethods=false)
class MaterialStreamingConfiguration implements WebMvcConfigurer {
 public void extendMessageConverters(List<HttpMessageConverter<?>> converters){converters.addFirst(new AbstractHttpMessageConverter<Resource>(MediaType.APPLICATION_OCTET_STREAM){
  protected boolean supports(Class<?> type){return type==Resource.class;}
  public boolean canWrite(Class<?> type,MediaType media){return false;}
  protected Resource readInternal(Class<? extends Resource> type,HttpInputMessage input)throws IOException{return new InputStreamResource(input.getBody());}
  protected void writeInternal(Resource resource,HttpOutputMessage output){throw new UnsupportedOperationException();}
 });}
}
