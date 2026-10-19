import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line front end.
 *
 * Usage: {@code java MainApp [vault-folder]} (the folder defaults to "vault" in the current directory).
 */
public class MainApp {

    private static final int MIN_MASTER_LENGTH = 8;
    private static final int MAX_UNLOCK_ATTEMPTS = 3;

    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : "vault").toAbsolutePath().normalize();
        Vault vault = new Vault(dir, new VaultCrypto());
        Terminal terminal = new Terminal();
        char[] master = null;

        System.out.println("StegoVault: logins encrypted and hidden inside images");
        System.out.println("Vault folder: " + dir);
        try {
            master = unlock(terminal, vault);
            if (master != null) {
                menu(terminal, vault, master);
            }
        } catch (Terminal.EndOfInput e) {
            System.out.println();
        } catch (IOException e) {
            System.out.println("❌ Can't read the vault folder: " + e.getMessage());
        } finally {
            if (master != null) {
                Arrays.fill(master, '\0');
            }
        }
        System.out.println("👋 Goodbye.");
    }

    /** Returns the master password, or null if the user couldn't unlock the vault. */
    private static char[] unlock(Terminal terminal, Vault vault) throws IOException {
        if (vault.list().isEmpty()) {
            System.out.println("\nNo saved logins yet. Choose a master password. It encrypts every login, and");
            System.out.println("there is no way to recover them if you forget it.");
            while (true) {
                char[] first = terminal.readSecret("New master password (at least " + MIN_MASTER_LENGTH + " characters): ");
                if (first.length < MIN_MASTER_LENGTH) {
                    System.out.println("⚠️ Too short.");
                    Arrays.fill(first, '\0');
                    continue;
                }
                char[] second = terminal.readSecret("Type it again: ");
                boolean same = Arrays.equals(first, second);
                Arrays.fill(second, '\0');
                if (same) {
                    return first;
                }
                Arrays.fill(first, '\0');
                System.out.println("⚠️ Those don't match. Try again.");
            }
        }

        for (int attempt = 1; attempt <= MAX_UNLOCK_ATTEMPTS; attempt++) {
            char[] password = terminal.readSecret("Master password: ");
            if (vault.checkPassword(password)) {
                System.out.println("✅ Unlocked.");
                return password;
            }
            Arrays.fill(password, '\0');
            System.out.println("❌ Wrong master password.");
        }
        System.out.println("❌ Too many wrong attempts.");
        return null;
    }

    private static void menu(Terminal terminal, Vault vault, char[] master) {
        while (true) {
            System.out.println("\n====== StegoVault ======");
            System.out.println("1. Save a login");
            System.out.println("2. Show a login");
            System.out.println("3. List saved websites");
            System.out.println("4. Delete a login");
            System.out.println("5. Exit");
            String choice = terminal.readLine("Choose an option (1-5): ").trim();
            try {
                switch (choice) {
                    case "1" -> save(terminal, vault, master);
                    case "2" -> show(terminal, vault, master);
                    case "3" -> list(vault);
                    case "4" -> delete(terminal, vault);
                    case "5" -> {
                        return;
                    }
                    default -> System.out.println("⚠️ Please enter a number from 1 to 5.");
                }
            } catch (IOException | IllegalArgumentException e) {
                System.out.println("❌ " + e.getMessage());
            }
        }
    }

    private static void save(Terminal terminal, Vault vault, char[] master) throws IOException {
        String website = askRequired(terminal, "Website: ").trim();
        Vault.fileNameFor(website); // rejects unusable names before asking for anything else
        if (vault.has(website) && !confirm(terminal, "A login for " + website + " is already saved. Replace it? (y/N): ")) {
            return;
        }
        String username = askRequired(terminal, "Username: ");
        char[] password = terminal.readSecret("Password: ");
        if (password.length == 0) {
            System.out.println("⚠️ The password can't be empty.");
            return;
        }
        String coverPath = terminal.readLine("Photo to hide it in (drag one here, or press Enter to generate an image): ");
        BufferedImage cover = coverPath.isBlank() ? null : CoverImage.load(Path.of(cleanPath(coverPath)));

        // Java Strings can't be wiped from memory, so the password lives on as one until garbage collection.
        Credential credential = new Credential(website, username, new String(password));
        Arrays.fill(password, '\0');
        Path saved = vault.store(credential, master, cover);
        System.out.println("✅ Encrypted and hidden in " + saved);
    }

    private static void show(Terminal terminal, Vault vault, char[] master) throws IOException {
        String website = askRequired(terminal, "Website: ").trim();
        if (!vault.has(website)) {
            System.out.println("⚠️ Nothing saved for " + website + ". Option 3 lists what is saved.");
            return;
        }
        try {
            System.out.println(vault.retrieve(website, master).toFormattedString());
        } catch (VaultException e) {
            System.out.println("❌ " + e.getMessage());
        }
    }

    private static void list(Vault vault) throws IOException {
        List<String> names = vault.list();
        if (names.isEmpty()) {
            System.out.println("No logins saved yet.");
            return;
        }
        System.out.println(names.size() + " saved:");
        for (String name : names) {
            System.out.println("  " + name);
        }
    }

    private static void delete(Terminal terminal, Vault vault) {
        String website = askRequired(terminal, "Website to delete: ").trim();
        if (!vault.has(website)) {
            System.out.println("⚠️ Nothing saved for " + website + ".");
            return;
        }
        if (!confirm(terminal, "Delete the login for " + website + "? (y/N): ")) {
            return;
        }
        if (vault.delete(website)) {
            System.out.println("🗑️ Deleted " + vault.pathFor(website).getFileName());
        } else {
            System.out.println("❌ Couldn't delete " + vault.pathFor(website));
        }
    }

    private static String askRequired(Terminal terminal, String prompt) {
        while (true) {
            String answer = terminal.readLine(prompt);
            if (!answer.isBlank()) {
                return answer;
            }
            System.out.println("⚠️ This can't be empty.");
        }
    }

    private static boolean confirm(Terminal terminal, String prompt) {
        return terminal.readLine(prompt).trim().toLowerCase().startsWith("y");
    }

    /**
     * Dragging a file into the macOS Terminal pastes its path with spaces escaped ("My\ Photo.jpg"), and some
     * terminals wrap it in quotes. Undo both, and expand a leading "~".
     */
    static String cleanPath(String input) {
        String path = input.trim();
        if (path.length() >= 2 && (path.startsWith("'") && path.endsWith("'") || path.startsWith("\"") && path.endsWith("\""))) {
            path = path.substring(1, path.length() - 1);
        } else {
            path = path.replace("\\ ", " ");
        }
        if (path.equals("~") || path.startsWith("~/")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
