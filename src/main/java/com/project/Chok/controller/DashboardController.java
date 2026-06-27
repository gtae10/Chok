package com.project.Chok.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/stocks/{ticker}")
    public String stockDetail() {
        return "stock-detail";
    }
}