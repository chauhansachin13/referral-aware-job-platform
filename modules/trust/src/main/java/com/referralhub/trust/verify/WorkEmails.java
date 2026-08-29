package com.referralhub.trust.verify;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether an address can prove employment at a company.
 *
 * <p>Two rules, both load-bearing. The domain must match the company's registered domain (or a
 * subdomain of it), and it must not be a free consumer provider — otherwise "verification" only
 * proves the person owns a Gmail account, which everyone does.
 */
public final class WorkEmails {

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Set<String> CONSUMER_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "hotmail.com",
            "outlook.com", "live.com", "icloud.com", "me.com", "aol.com", "proton.me",
            "protonmail.com", "gmx.com", "mail.com", "yandex.com", "zoho.com", "rediffmail.com");

    private WorkEmails() {
    }

    public static boolean isWellFormed(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }

    public static String domainOf(String email) {
        if (!isWellFormed(email)) {
            throw new IllegalArgumentException("Not a valid email address");
        }
        return email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isConsumerDomain(String domain) {
        return CONSUMER_DOMAINS.contains(domain.toLowerCase(Locale.ROOT));
    }

    /**
     * @param companyDomain the domain on the company record, e.g. {@code acme.com}
     * @return true when the address is at that domain or a subdomain of it
     */
    public static boolean belongsToCompany(String email, String companyDomain) {
        if (!isWellFormed(email) || companyDomain == null || companyDomain.isBlank()) {
            return false;
        }
        String domain = domainOf(email);
        String expected = companyDomain.toLowerCase(Locale.ROOT).trim();
        if (isConsumerDomain(domain)) {
            return false;
        }
        // "eu.acme.com" counts; "notacme.com" and "acme.com.evil.net" do not.
        return domain.equals(expected) || domain.endsWith("." + expected);
    }
}
