package com.creditscore.platform.ingestion.mobilemoney;

/**
 * M-Pesa-style transaction types. STK_PUSH (customer-initiated push payment) and C2B
 * (customer-to-business, e.g. paybill/till payments) represent money flowing into the
 * business. B2C (business-to-customer, e.g. refunds/disbursements) represents money
 * flowing out.
 */
public enum MobileMoneyTransactionType {
    STK_PUSH,
    C2B,
    B2C
}
