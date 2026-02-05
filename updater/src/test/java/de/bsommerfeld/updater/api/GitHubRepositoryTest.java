package de.bsommerfeld.updater.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubRepositoryTest {

    @Test
    void constructor_shouldStoreOwnerAndRepo() {
        var repo = new GitHubRepository("owner", "repo");
        assertEquals("owner", repo.owner());
        assertEquals("repo", repo.repo());
    }

    @Test
    void of_shouldParseValidSlug() {
        var repo = GitHubRepository.of("octocat/hello-world");
        assertEquals("octocat", repo.owner());
        assertEquals("hello-world", repo.repo());
    }

    @Test
    void of_shouldThrowForNoSlash() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepository.of("noslash"));
    }

    @Test
    void of_shouldThrowForTooManySlashes() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepository.of("a/b/c"));
    }

    @Test
    void of_shouldThrowForBlankOwner() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepository.of("/repo"));
    }

    @Test
    void of_shouldThrowForBlankRepo() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepository.of("owner/"));
    }

    @Test
    void of_shouldThrowForEmptyString() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepository.of(""));
    }

    @Test
    void latestReleaseUrl_shouldReturnCorrectApiUrl() {
        var repo = new GitHubRepository("bsommerfeld", "wsbg-terminal");
        assertEquals("https://api.github.com/repos/bsommerfeld/wsbg-terminal/releases/latest",
                repo.latestReleaseUrl());
    }

    @Test
    void equality_shouldMatchOnBothFields() {
        var a = GitHubRepository.of("owner/repo");
        var b = GitHubRepository.of("owner/repo");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
