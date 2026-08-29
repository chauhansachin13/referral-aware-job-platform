package com.referralhub.trust.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkEmailsTest {

    @ParameterizedTest
    @DisplayName("an address at the company domain or a subdomain of it proves employment")
    @CsvSource({
            "sachin@acme.com,        acme.com, true",
            "sachin@eu.acme.com,     acme.com, true",
            "sachin@mail.eng.acme.com, acme.com, true",
            "SACHIN@ACME.COM,        acme.com, true"
    })
    void acceptsCompanyDomains(String email, String companyDomain, boolean expected) {
        assertThat(WorkEmails.belongsToCompany(email, companyDomain)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("lookalike domains are rejected")
    @CsvSource({
            "sachin@notacme.com,          acme.com",
            "sachin@acme.com.evil.net,    acme.com",
            "sachin@acme.co,              acme.com",
            "sachin@evilacme.com,         acme.com"
    })
    void rejectsLookalikeDomains(String email, String companyDomain) {
        // "acme.com.evil.net" ends with a domain the naive check would accept.
        assertThat(WorkEmails.belongsToCompany(email, companyDomain)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("a free consumer address proves nothing and is refused")
    @ValueSource(strings = {"sachin@gmail.com", "sachin@outlook.com", "sachin@proton.me",
            "sachin@rediffmail.com"})
    void rejectsConsumerDomains(String email) {
        String domain = WorkEmails.domainOf(email);

        assertThat(WorkEmails.isConsumerDomain(domain)).isTrue();
        assertThat(WorkEmails.belongsToCompany(email, domain)).isFalse();
    }

    @Test
    @DisplayName("malformed addresses are rejected before any domain logic runs")
    void rejectsMalformedAddresses() {
        for (String bad : new String[] {null, "", "not-an-email", "@acme.com", "a@b", "a b@acme.com"}) {
            assertThat(WorkEmails.isWellFormed(bad)).isFalse();
            assertThat(WorkEmails.belongsToCompany(bad, "acme.com")).isFalse();
        }
        assertThatThrownBy(() -> WorkEmails.domainOf("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a company with no registered domain matches nothing")
    void missingCompanyDomainMatchesNothing() {
        assertThat(WorkEmails.belongsToCompany("sachin@acme.com", null)).isFalse();
        assertThat(WorkEmails.belongsToCompany("sachin@acme.com", "  ")).isFalse();
    }
}
