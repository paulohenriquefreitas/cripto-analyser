package br.com.bauzin.apps.threecandles;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class FrontendCors implements WebMvcConfigurer {
 @Override public void addCorsMappings(CorsRegistry registry) {
  registry.addMapping("/api/**").allowedOrigins("http://localhost:5175","http://127.0.0.1:5175","http://localhost:4175","http://127.0.0.1:4175")
   .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS").allowedHeaders("*");
 }
}
