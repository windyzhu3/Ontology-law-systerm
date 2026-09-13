package io.github.windyzhu3.ontologylaw.api;

import java.util.Map;

/** Identity wire preconditions are strong string tags, separate from the business resource envelope. */
final class IdentityHttpFailure extends RuntimeException {
    final String code,etag;final Map<String,Object> receipt;
    IdentityHttpFailure(String code,String etag,Map<String,Object> receipt){super(code,null,false,false);this.code=code;this.etag=etag;this.receipt=receipt;}
}
