import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads what the user types. In a real terminal, passwords are read without echoing them to the screen.
 * When input ends (Ctrl-D, or the end of piped input) it throws {@link EndOfInput} instead of crashing.
 */
final class Terminal {

    static final class EndOfInput extends RuntimeException {
        EndOfInput() {
            super("end of input");
        }
    }

    private final Console console = System.console();
    private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    String readLine(String prompt) {
        String line;
        if (console != null) {
            line = console.readLine("%s", prompt);
        } else {
            System.out.print(prompt);
            System.out.flush();
            try {
                line = reader.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (line == null) {
            throw new EndOfInput();
        }
        return line;
    }

    /** Returns a char[] rather than a String so the caller can wipe it once it's no longer needed. */
    char[] readSecret(String prompt) {
        if (console == null) {
            return readLine(prompt).toCharArray();
        }
        char[] secret = console.readPassword("%s", prompt);
        if (secret == null) {
            throw new EndOfInput();
        }
        return secret;
    }
}
