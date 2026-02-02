
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Alke Wallet - Versión simplificada (solo USD y CLP)
 */
public class AlkeWalletApp {
    public static void main(String[] args) {
        AccountRepository repo = new InMemoryAccountRepository();
        ExchangeRateProvider rates = new SimpleExchangeRateProvider();
        AccountService service = new AccountServiceImpl(repo, rates);

        new Menu(service).start();
    }
}

/* =========================
   =======   MODEL   =======
   ========================= */

enum Currency {
    USD, CLP
}

/** Value Object para representar montos en una moneda. */
final class Money {
    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final Currency currency;

    public Money(BigDecimal amount, Currency currency) {
        this.amount = amount.setScale(SCALE, ROUND);
        this.currency = currency;
    }

    public BigDecimal amount() { return amount; }
    public Currency currency() { return currency; }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), currency);
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency)
            throw new RuntimeException("Monedas distintas.");
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}

enum TransactionType {
    DEPOSIT, WITHDRAW, CONVERT
}

class Transaction {
    private final String id = UUID.randomUUID().toString();
    private final LocalDateTime timestamp = LocalDateTime.now();
    private final TransactionType type;
    private final Money amount;
    private final Money balanceAfter;
    private final String notes;

    public Transaction(TransactionType type, Money amount, Money balanceAfter, String notes) {
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.notes = notes;
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + type + " " + amount + " -> saldo: " + balanceAfter +
                (notes == null ? "" : " (" + notes + ")");
    }
}

class Account {
    private final String id = UUID.randomUUID().toString();
    private final String ownerName;
    private Money balance;
    private final List<Transaction> transactions = new ArrayList<>();

    public Account(String owner, Currency currency) {
        this.ownerName = owner;
        this.balance = new Money(new BigDecimal("0"), currency);
    }

    public String getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public Money getBalance() { return balance; }
    public Currency getCurrency() { return balance.currency(); }
    public void setBalance(Money m) { balance = m; }
    public void addTransaction(Transaction t) { transactions.add(t); }
    public List<Transaction> getTransactions() { return transactions; }

    @Override
    public String toString() {
        return "Cuenta{id=" + id + ", owner=" + ownerName + ", balance=" + balance + "}";
    }
}

/* =========================
   =====  REPOSITORY  ======
   ========================= */

interface AccountRepository {
    Account save(Account a);
    Optional<Account> findById(String id);
}

class InMemoryAccountRepository implements AccountRepository {
    private final Map<String, Account> data = new HashMap<>();

    public Account save(Account a) {
        data.put(a.getId(), a);
        return a;
    }

    public Optional<Account> findById(String id) {
        return Optional.ofNullable(data.get(id));
    }
}

/* =========================
   ======  SERVICES  =======
   ========================= */

interface ExchangeRateProvider {
    BigDecimal rate(Currency from, Currency to);
}

/** Tasas simples USD ↔ CLP */
class SimpleExchangeRateProvider implements ExchangeRateProvider {
    // 1 USD ≈ 900 CLP
    private static final BigDecimal USD_TO_CLP = new BigDecimal("900");
    private static final BigDecimal CLP_TO_USD = BigDecimal.ONE.divide(USD_TO_CLP, 10, RoundingMode.HALF_UP);

    public BigDecimal rate(Currency from, Currency to) {
        if (from == to) return BigDecimal.ONE;
        if (from == Currency.USD && to == Currency.CLP) return USD_TO_CLP;
        if (from == Currency.CLP && to == Currency.USD) return CLP_TO_USD;
        throw new RuntimeException("Par de monedas no soportado");
    }
}

interface AccountService {
    Account createAccount(String owner, Currency currency);
    Money getBalance(String id);
    Account deposit(String id, Money amount);
    Account withdraw(String id, BigDecimal amount);
    Account convertAll(String id, Currency target);
    List<Transaction> getTransactions(String id);
}

class AccountServiceImpl implements AccountService {
    private final AccountRepository repo;
    private final ExchangeRateProvider rates;

    public AccountServiceImpl(AccountRepository r, ExchangeRateProvider p) {
        this.repo = r;
        this.rates = p;
    }

    private Account acc(String id) {
        return repo.findById(id).orElseThrow(() -> new RuntimeException("Cuenta no encontrada"));
    }

    public Account createAccount(String owner, Currency currency) {
        Account a = new Account(owner, currency);
        return repo.save(a);
    }

    public Money getBalance(String id) {
        return acc(id).getBalance();
    }

    public Account deposit(String id, Money amount) {
        Account a = acc(id);

        Money converted = amount.currency() == a.getCurrency()
                ? amount
                : convert(amount, a.getCurrency());

        a.setBalance(a.getBalance().add(converted));
        a.addTransaction(new Transaction(TransactionType.DEPOSIT, converted, a.getBalance(), null));
        return a;
    }

    public Account withdraw(String id, BigDecimal amount) {
        Account a = acc(id);
        Money m = new Money(amount, a.getCurrency());

        if (a.getBalance().amount().compareTo(amount) < 0)
            throw new RuntimeException("Fondos insuficientes");

        a.setBalance(a.getBalance().subtract(m));
        a.addTransaction(new Transaction(TransactionType.WITHDRAW, m, a.getBalance(), null));
        return a;
    }

    public Account convertAll(String id, Currency target) {
        Account a = acc(id);
        if (a.getCurrency() == target) return a;

        Money before = a.getBalance();
        Money after = convert(before, target);

        a.setBalance(after);
        a.addTransaction(new Transaction(TransactionType.CONVERT, after, after, "Conversión total"));
        return a;
    }

    public List<Transaction> getTransactions(String id) {
        return acc(id).getTransactions();
    }

    private Money convert(Money m, Currency to) {
        BigDecimal r = rates.rate(m.currency(), to);
        BigDecimal result = m.amount().multiply(r).setScale(2, RoundingMode.HALF_UP);
        return new Money(result, to);
    }
}

/* =========================
   =========  UI  ==========
   ========================= */

class Menu {
    private final Scanner sc = new Scanner(System.in);
    private final AccountService service;
    private String currentAccountId = null;

    public Menu(AccountService service) { this.service = service; }

    public void start() {
        int o;
        do {
            menu();
            o = readInt("Opción: ");
            try {
                switch (o) {
                    case 1 -> crear();
                    case 2 -> saldo();
                    case 3 -> depositar();
                    case 4 -> retirar();
                    case 5 -> convertir();
                    case 6 -> transacciones();
                    case 0 -> System.out.println("Adiós!");
                    default -> System.out.println("Opción inválida.");
                }
            } catch (Exception e) { System.out.println("⚠️ " + e.getMessage()); }
        } while (o != 0);
    }

    private void menu() {
        System.out.println("\n=== ALKE WALLET ===");
        System.out.println("Cuenta: " + (currentAccountId == null ? "(ninguna)" : currentAccountId));
        System.out.println("1) Crear cuenta");
        System.out.println("2) Ver saldo");
        System.out.println("3) Depositar");
        System.out.println("4) Retirar");
        System.out.println("5) Convertir saldo USD/CLP");
        System.out.println("6) Ver transacciones");
        System.out.println("0) Salir");
    }

    private void crear() {
        System.out.print("Nombre: ");
        String n = sc.nextLine();

        Currency c = readCurrency("Moneda inicial (USD/CLP): ");
        Account a = service.createAccount(n, c);
        currentAccountId = a.getId();

        System.out.println("Cuenta creada: " + a);
    }

    private void saldo() {
        System.out.println("Saldo: " + service.getBalance(requireAccount()));
    }

    private void depositar() {
        Currency c = readCurrency("Moneda del depósito (USD/CLP): ");
        BigDecimal amount = readAmount("Monto: ");
        Account a = service.deposit(requireAccount(), new Money(amount, c));
        System.out.println("Nuevo saldo: " + a.getBalance());
    }

    private void retirar() {
        BigDecimal amount = readAmount("Monto: ");
        Account a = service.withdraw(requireAccount(), amount);
        System.out.println("Nuevo saldo: " + a.getBalance());
    }

    private void convertir() {
        Currency t = readCurrency("Convertir a (USD/CLP): ");
        Account a = service.convertAll(requireAccount(), t);
        System.out.println("Nuevo saldo: " + a.getBalance());
    }

    private void transacciones() {
        List<Transaction> tx = service.getTransactions(requireAccount());
        if (tx.isEmpty()) System.out.println("No hay transacciones.");
        else tx.forEach(System.out::println);
    }

    /* Helpers UI */
    private String requireAccount() {
        if (currentAccountId == null)
            throw new RuntimeException("Primero crea una cuenta.");
        return currentAccountId;
    }

    private int readInt(String msg) {
        while (true) {
            try {
                System.out.print(msg);
                return Integer.parseInt(sc.nextLine());
            } catch (Exception e) {
                System.out.println("Número inválido.");
            }
        }
    }

    private BigDecimal readAmount(String msg) {
        while (true) {
            try {
                System.out.print(msg);
                BigDecimal b = new BigDecimal(sc.nextLine());
                if (b.signum() > 0) return b;
            } catch (Exception ignored) {}
            System.out.println("Monto inválido.");
        }
    }

    private Currency readCurrency(String msg) {
        while (true) {
            System.out.print(msg);
            try {
                return Currency.valueOf(sc.nextLine().trim().toUpperCase());
            } catch (Exception e) {
                System.out.println("Solo USD o CLP.");
            }
        }
    }
}
