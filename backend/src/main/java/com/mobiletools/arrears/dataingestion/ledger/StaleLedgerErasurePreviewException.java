package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class StaleLedgerErasurePreviewException extends RuntimeException {
    public StaleLedgerErasurePreviewException(String message) { super(message); }
}
