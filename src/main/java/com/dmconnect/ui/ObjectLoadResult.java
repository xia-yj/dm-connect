package com.dmconnect.ui;

import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.ResultTable;
import com.dmconnect.model.TableDetails;

record ObjectLoadResult(
        DatabaseObject object,
        TableDetails details,
        String detailsError,
        ResultTable preview,
        String previewError,
        String ddl,
        String ddlError) {
}
