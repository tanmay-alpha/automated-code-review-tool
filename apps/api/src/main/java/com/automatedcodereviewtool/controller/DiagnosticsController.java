package com.automatedcodereviewtool.controller;

import com.automatedcodereviewtool.dto.response.DiagnosticsResponse;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.service.DiagnosticsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/diagnostics} (JWT): dependency health and the caller's
 * queue depth. Always 200 — a failing dependency is reported in the body
 * as {@code degraded}/{@code down}, never as an error response.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final DiagnosticsService diagnosticsService;

    public DiagnosticsController(DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping
    public DiagnosticsResponse diagnostics(@AuthenticationPrincipal User caller) {
        return diagnosticsService.check(caller);
    }
}
