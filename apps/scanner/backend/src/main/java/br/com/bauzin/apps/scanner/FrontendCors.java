package br.com.bauzin.apps.scanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class FrontendCors implements WebMvcConfigurer {
 @Override public void addCorsMappings(CorsRegistry registry) {
  registry.addMapping("/api/**").allowedOrigins("http://localhost:5173","http://127.0.0.1:5173","http://localhost:4173","http://127.0.0.1:4173")
   .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS").allowedHeaders("*");
 }
}
