package app.vanishr.crypto;

import java.util.List;

public interface SecureVault {
    byte[] get(String name);
    void put(String name, byte[] value);
    void remove(String name);
    List<String> names(String prefix);
    <Result> Result transaction(Operation<Result> operation) throws Exception;

    @FunctionalInterface
    interface Operation<Result> {
        Result run() throws Exception;
    }
}