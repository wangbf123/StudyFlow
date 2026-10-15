package com.studyflow.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestTraceFilterTest {
    @Test void validRequestIdPropagatesAndIsClearedAfterRequest() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("X-Request-Id","workspace-test-42");
        var response=new MockHttpServletResponse();
        new RequestTraceFilter().doFilter(request,response,(req,res)->assertEquals("workspace-test-42",MDC.get("requestId")));
        assertEquals("workspace-test-42",response.getHeader("X-Request-Id"));assertNull(MDC.get("requestId"));
    }
    @Test void untrustedRequestIdIsReplacedAndPreviousContextIsRestored() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("X-Request-Id","unsafe\r\nheader");
        var response=new MockHttpServletResponse();MDC.put("requestId","outer");
        try {
            assertThrows(RuntimeException.class,()->new RequestTraceFilter().doFilter(request,response,(req,res)->{throw new RuntimeException();}));
            assertTrue(response.getHeader("X-Request-Id").matches("[a-f0-9-]{36}"));assertEquals("outer",MDC.get("requestId"));
        } finally { MDC.clear(); }
    }
}
