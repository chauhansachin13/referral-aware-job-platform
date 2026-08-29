package com.referralhub.common.error;

public class NotFoundException extends DomainException {

    public NotFoundException(String what, Object id) {
        super("not_found", what + " " + id + " does not exist");
    }
}
