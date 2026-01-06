package ma.fstt.bookingservice.config;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class AuthAspect {

    @Around("@annotation(requiresRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequiresRole requiresRole) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new RuntimeException("Request context not found");
        }

        HttpServletRequest request = attributes.getRequest();
        String userId = request.getHeader("X-User-Id");
        String userRoles = request.getHeader("X-User-Roles");

        if (userId == null) {
            throw new SecurityException("Not authenticated: Missing X-User-Id header");
        }

        String requiredRole = requiresRole.value();
        if (userRoles == null || !userRoles.contains(requiredRole)) {
            // Special case: owners accessing their own resources might be handled
            // differently,
            // but for @RequiresRole("ADMIN"), we strictly enforce ADMIN role.
            throw new SecurityException("Access Denied: Requires role " + requiredRole);
        }

        return joinPoint.proceed();
    }
}
