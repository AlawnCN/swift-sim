package com.example.swiftsim.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.*;

/**
 * `curl --data-binary @file` sends Content-Type application/x-www-form-urlencoded by default, which would make the
 * servlet container consume the raw SWIFT message as form parameters. For POST /api/messages we treat any body as raw
 * text and only take parameters from the query string.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RawBodyFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(req.getMethod()) && (req.getRequestURI().equals("/api/messages") || req.getRequestURI().equals("/ai/v1/validate"))
                && req.getContentType() != null && req.getContentType().startsWith("application/x-www-form-urlencoded")) {
            Map<String, String[]> params = new LinkedHashMap<>();
            if (req.getQueryString() != null) {
                UriComponentsBuilder.fromUriString("?" + req.getQueryString()).build(false).getQueryParams()
                        .forEach((k, v) -> params.put(k, v.stream().map(x -> x == null ? "" : java.net.URLDecoder.decode(x, java.nio.charset.StandardCharsets.UTF_8)).toArray(String[]::new)));
            }
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public String getContentType() { return "text/plain;charset=UTF-8"; }
                @Override public String getHeader(String n) { return "content-type".equalsIgnoreCase(n) ? getContentType() : super.getHeader(n); }
                @Override public Enumeration<String> getHeaders(String n) {
                    return "content-type".equalsIgnoreCase(n) ? Collections.enumeration(List.of(getContentType())) : super.getHeaders(n);
                }
                @Override public String getParameter(String n) { String[] v = params.get(n); return v == null || v.length == 0 ? null : v[0]; }
                @Override public Map<String, String[]> getParameterMap() { return params; }
                @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(params.keySet()); }
                @Override public String[] getParameterValues(String n) { return params.get(n); }
            }, res);
            return;
        }
        chain.doFilter(req, res);
    }
}
