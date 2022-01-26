package name.abuchen.portfolio.datatransfer.pdf;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.SecurityCache;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.model.Annotated;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.CrossEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.TextUtil;

public abstract class AbstractPDFExtractor implements Extractor
{
    protected static final String FLAG_WITHHOLDING_TAX_FOUND = Boolean.FALSE.toString();

    private final NumberFormat numberFormat = NumberFormat.getInstance(Locale.GERMANY);

    private final Client client;
    private SecurityCache securityCache;

    private final List<String> bankIdentifier = new ArrayList<>();
    private final List<DocumentType> documentTypes = new ArrayList<>();

    public AbstractPDFExtractor(Client client)
    {
        this.client = client;
    }

    public final Client getClient()
    {
        return client;
    }

    protected final void addDocumentTyp(DocumentType type)
    {
        this.documentTypes.add(type);
    }

    protected final void addBankIdentifier(String identifier)
    {
        this.bankIdentifier.add(identifier);
    }

    public List<String> getBankIdentifier()
    {
        return bankIdentifier;
    }

    @Override
    public List<Item> extract(SecurityCache securityCache, Extractor.InputFile inputFile, List<Exception> errors)
    {
        // careful: security cache makes extractor stateful
        this.securityCache = securityCache;

        List<Item> results = new ArrayList<>();

        if (!(inputFile instanceof PDFInputFile))
            throw new IllegalArgumentException();

        String text = ((PDFInputFile) inputFile).getText();
        results.addAll(extract(inputFile.getFile().getName(), text, errors));

        this.securityCache = null;

        return results;
    }

    private final List<Item> extract(String filename, String text, List<Exception> errors)
    {
        try
        {
            checkBankIdentifier(filename, text);

            List<Item> items = parseDocumentTypes(documentTypes, filename, text);

            if (items.isEmpty())
            {
                errors.add(new UnsupportedOperationException(
                                MessageFormat.format(Messages.PDFdbMsgCannotDetermineFileType, getLabel(), filename)));
            }

            for (Item item : items)
            {
                Annotated subject = item.getSubject();

                if (subject instanceof Transaction)
                    ((Transaction) subject).setSource(filename);
                else if (subject instanceof CrossEntry)
                    ((CrossEntry) subject).setSource(filename);
                else if (subject.getNote() == null || TextUtil.strip(subject.getNote()).length() == 0)
                    item.getSubject().setNote(filename);
                else
                    item.getSubject().setNote(
                                    TextUtil.strip(item.getSubject().getNote()).concat(" | ").concat(filename)); //$NON-NLS-1$
            }

            return items;
        }
        catch (IllegalArgumentException e)
        {
            errors.add(new IllegalArgumentException(e.getMessage() + " @ " + filename, e)); //$NON-NLS-1$
            return Collections.emptyList();
        }
        catch (NullPointerException e)
        {
            // NPE should not block further processing. Print full stack trace
            // to error log to enable further investigation

            IllegalArgumentException error = new IllegalArgumentException("NullPointerException @ " + filename, e); //$NON-NLS-1$
            PortfolioLog.error(error);
            errors.add(error);
            return Collections.emptyList();
        }
        catch (UnsupportedOperationException e)
        {
            errors.add(e);
            return Collections.emptyList();
        }
    }

    protected final List<Item> parseDocumentTypes(List<DocumentType> documentTypes, String filename, String text)
    {
        List<Item> items = new ArrayList<>();
        for (DocumentType type : documentTypes)
        {
            if (type.matches(text))
                type.parse(filename, items, text);
        }
        return items;
    }

    private void checkBankIdentifier(String filename, String text)
    {
        if (bankIdentifier.isEmpty())
            bankIdentifier.add(getLabel());

        for (String identifier : bankIdentifier)
            if (text.contains(identifier))
                return;

        throw new UnsupportedOperationException( //
                        MessageFormat.format(Messages.PDFMsgFileNotSupported, filename, getLabel()));
    }

    protected Security getOrCreateSecurity(Map<String, String> values)
    {
        String isin = values.get("isin"); //$NON-NLS-1$
        if (isin != null)
            isin = isin.trim();

        String tickerSymbol = values.get("tickerSymbol"); //$NON-NLS-1$
        if (tickerSymbol != null)
            tickerSymbol = tickerSymbol.trim();

        String wkn = values.get("wkn"); //$NON-NLS-1$
        if (wkn != null)
            wkn = wkn.trim();

        String name = values.get("name"); //$NON-NLS-1$
        if (name != null)
            name = name.trim();

        String nameRowTwo = values.get("nameContinued"); //$NON-NLS-1$
        if (nameRowTwo != null)
            name = name + " " + nameRowTwo.trim(); //$NON-NLS-1$

        Security security = securityCache.lookup(isin, tickerSymbol, wkn, name, () -> {
            Security s = new Security();
            s.setCurrencyCode(asCurrencyCode(values.get("currency"))); //$NON-NLS-1$
            return s;
        });

        if (security == null)
            throw new IllegalArgumentException("Unable to construct security: " + values.toString()); //$NON-NLS-1$

        return security;
    }

    protected long asShares(String value)
    {
        try
        {
            return Math.round(numberFormat.parse(value).doubleValue() * Values.Share.factor());
        }
        catch (ParseException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    protected String asCurrencyCode(String currency)
    {
        // ensure that the security is always created with a valid currency code
        if (currency == null)
            return client.getBaseCurrency();

        CurrencyUnit unit = CurrencyUnit.getInstance(currency.trim());
        return unit == null ? client.getBaseCurrency() : unit.getCurrencyCode();
    }

    protected long asAmount(String value)
    {
        try
        {
            return Math.abs(Math.round(numberFormat.parse(value).doubleValue() * Values.Amount.factor()));
        }
        catch (ParseException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    protected BigDecimal asExchangeRate(String value)
    {
        try
        {
            return BigDecimal.valueOf(numberFormat.parse(value).doubleValue());
        }
        catch (ParseException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    protected LocalDateTime asDate(String value)
    {
        return PDFExtractorUtils.asDate(value);
    }

    protected LocalTime asTime(String value)
    {
        return PDFExtractorUtils.asTime(value);
    }

    protected LocalDateTime asDate(String date, String time)
    {
        return PDFExtractorUtils.asDate(date, time);
    }

    protected void processTaxEntries(Object t, Map<String, String> v, DocumentType type)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax"))); //$NON-NLS-1$ //$NON-NLS-2$
            PDFExtractorUtils.checkAndSetTax(tax, (name.abuchen.portfolio.model.Transaction) t, type);
        }
        else
        {
            Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax"))); //$NON-NLS-1$ //$NON-NLS-2$
            PDFExtractorUtils.checkAndSetTax(tax,
                            ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
        }
    }

    protected void processFeeEntries(Object t, Map<String, String> v, DocumentType type)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            Money fee = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("fee"))); //$NON-NLS-1$ //$NON-NLS-2$
            PDFExtractorUtils.checkAndSetFee(fee, (name.abuchen.portfolio.model.Transaction) t, type);
        }
        else
        {
            Money fee = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("fee"))); //$NON-NLS-1$ //$NON-NLS-2$
            PDFExtractorUtils.checkAndSetFee(fee,
                            ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
        }
    }

    protected void processWithHoldingTaxEntries(Object t, Map<String, String> v, String taxType, DocumentType type)
    {
        /***
         * If it is a "withholding tax", the other types of "creditable
         * withholding tax" are not to be considered.
         */
        if (checkWithHoldingTax(taxType, type))
        {
            if (t instanceof name.abuchen.portfolio.model.Transaction)
            {
                Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get(taxType))); //$NON-NLS-1$
                PDFExtractorUtils.checkAndSetTax(tax, (name.abuchen.portfolio.model.Transaction) t, type);
            }
            else
            {
                Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get(taxType))); //$NON-NLS-1$
                PDFExtractorUtils.checkAndSetTax(tax,
                                ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
            }
        }
    }

    protected boolean checkWithHoldingTax(String taxType, DocumentType type)
    {
        if (Boolean.valueOf(type.getCurrentContext().get(FLAG_WITHHOLDING_TAX_FOUND)))
        {
            if ("creditableWithHoldingTax".equalsIgnoreCase(taxType)) //$NON-NLS-1$
                return false;
        }
        return true;
    }
}
