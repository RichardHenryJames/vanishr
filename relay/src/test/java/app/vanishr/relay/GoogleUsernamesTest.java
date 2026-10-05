package app.vanishr.relay;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class GoogleUsernamesTest {
    @Test void vocabularyIsUniqueLowercaseAndFitsTheUsernameLimit() {
        assertTrue(GoogleUsernames.FIRST_WORDS.size() >= 500);
        assertTrue(GoogleUsernames.SECOND_WORDS.size() >= 500);
        assertEquals(GoogleUsernames.FIRST_WORDS.size(), new HashSet<>(GoogleUsernames.FIRST_WORDS).size());
        assertEquals(GoogleUsernames.SECOND_WORDS.size(), new HashSet<>(GoogleUsernames.SECOND_WORDS).size());
        long combinations = (long) GoogleUsernames.FIRST_WORDS.size() * GoogleUsernames.SECOND_WORDS.size() * 9000;
        assertTrue(combinations >= 2_250_000_000L);
        for (String first : GoogleUsernames.FIRST_WORDS) {
            assertTrue(first.matches("[a-z]+"));
            for (String second : GoogleUsernames.SECOND_WORDS) {
                assertTrue(second.matches("[a-z]+"));
                assertTrue((first + "-" + second + "-9999").matches(AccountDirectory.USERNAME_PATTERN));
            }
        }
    }

    @Test void bothNumberAndVocabularyBoundariesUseTheExactRequestedFormat() {
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextInt(1000, 10000)).thenReturn(1000);
        assertEquals("amber-acorn-1000", GoogleUsernames.next(random));
        when(random.nextInt(anyInt())).thenAnswer(call -> (int) call.getArgument(0) - 1);
        when(random.nextInt(1000, 10000)).thenReturn(9999);
        assertEquals("versatile-tapestry-9999", GoogleUsernames.next(random));
    }

    @Test void generatedNamesUseTwoKnownWordsAndFourIndependentDigits() {
        Random random = new Random(5300);
        HashSet<String> names = new HashSet<>();
        for (int index = 0; index < 10_000; index++) {
            String name = GoogleUsernames.next(random);
            assertTrue(name.matches("[a-z]+-[a-z]+-[1-9][0-9]{3}"));
            String[] parts = name.split("-");
            assertTrue(GoogleUsernames.FIRST_WORDS.contains(parts[0]));
            assertTrue(GoogleUsernames.SECOND_WORDS.contains(parts[1]));
            names.add(name);
        }
        assertTrue(names.size() > 9900);
        assertTrue(GoogleUsernames.next().matches("[a-z]+-[a-z]+-[1-9][0-9]{3}"));
    }
}
