package com.flashsale.common.web;

interface ApiAuditPersistence {
    void submit(ApiAuditLog log, Runnable completion);
}
