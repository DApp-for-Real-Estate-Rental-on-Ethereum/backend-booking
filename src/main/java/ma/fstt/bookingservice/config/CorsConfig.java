package ma.fstt.bookingservice.config;

import org.springframework.context.annotation.Configuration;

// CORS is handled by the API Gateway - no need to configure here
// Removing CorsFilter bean to prevent duplicate CORS headers

@Configuration
public class CorsConfig {
    // Empty - CORS handled by Gateway
}
