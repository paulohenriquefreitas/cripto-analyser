package br.com.bauzin.apps.winm5;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class FrontendCors implements WebMvcConfigurer {
 @Override public void addCorsMappings(CorsRegistry registry) {
  registry.addMapping("/api/**").allowedOrigins("http://localhost:5174","http://127.0.0.1:5174","http://localhost:4174","http://127.0.0.1:4174")
   .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS").allowedHeaders("*");
 }
}
