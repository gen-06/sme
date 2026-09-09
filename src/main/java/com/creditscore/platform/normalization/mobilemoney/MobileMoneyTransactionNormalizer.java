package com.creditscore.platform.normalization.mobilemoney;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.RawTransactionRecord;
import com.creditscore.platform.ingestion.mobilemoney.MobileMoneyRawTransaction;
import com.creditscore.platform.ingestion.mobilemoney.MobileMoneyTransactionStatus;
import com.creditscore.platform.ingestion.mobilemoney.MobileMoneyTransactionType;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionNormalizer;
import com.creditscore.platform.normalization.TransactionStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MobileMoneyTransactionNormalizer implements TransactionNormalizer {

    @Override
    public AdapterType supports() {
        return AdapterType.MOBILE_MONEY;
    }

    @Override
    public List<Transaction> normalize(DataSource dataSource, List<RawTransactionRecord> rawRecords) {
        return rawRecords.stream()
                .map(MobileMoneyRawTransaction.class::cast)
                .map(raw -> toTransaction(dataSource, raw))
                .toList();
    }

    private Transaction toTransaction(DataSource dataSource, MobileMoneyRawTransaction raw) {
        Direction direction = raw.transactionType() == MobileMoneyTransactionType.B2C
                ? Direction.OUTFLOW
                : Direction.INFLOW;
        TransactionStatus status = raw.transactionStatus() == MobileMoneyTransactionStatus.COMPLETED
                ? TransactionStatus.COMPLETED
                : TransactionStatus.FAILED;

        return new Transaction(
                dataSource.getId(),
                dataSource.getBusinessId(),
                raw.mpesaReceiptNumber(),
                raw.amount(),
                dataSource.getCurrency(),
                raw.transactionDate(),
                direction,
                raw.phoneNumberMasked(),
                AdapterType.MOBILE_MONEY,
                status);
    }
}
