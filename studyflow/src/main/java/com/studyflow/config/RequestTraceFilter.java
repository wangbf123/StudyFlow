package com.studyflow.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied=request.getHeader("X-Request-Id");
        String id=supplied!=null && supplied.matches("[a-zA-Z0-9._-]{1,64}") ? supplied : UUID.randomUUID().toString();
        String previous=MDC.get("requestId");
        MDC.put("requestId",id);response.setHeader("X-Request-Id",id);
        try { chain.doFilter(request,response); }
        finally { if(previous==null) MDC.remove("requestId"); else MDC.put("requestId",previous); }
    }
}
