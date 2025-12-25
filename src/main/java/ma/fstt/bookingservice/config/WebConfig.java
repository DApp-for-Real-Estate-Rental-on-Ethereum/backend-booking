package ma.fstt.bookingservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// CORS is handled by the API Gateway - no need to configure here

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // Empty - CORS handled by Gateway
}
