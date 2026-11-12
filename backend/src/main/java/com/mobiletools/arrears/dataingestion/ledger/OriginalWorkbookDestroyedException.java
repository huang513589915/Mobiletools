package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Returned when a batch remains traceable after its original file was explicitly destroyed. */
@ResponseStatus(HttpStatus.GONE)
public class OriginalWorkbookDestroyedException extends RuntimeException {
    public OriginalWorkbookDestroyedException() {
        super("该批次的原始工作簿已永久删除");
    }
}
