package com.whereq.dosbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The privacy policy, and the link the app gives to it.
 *
 * WhereQ-DOSBox is free and open-source software, published from its own
 * repository. That is where its privacy policy lives - in the repository, next
 * to the code and the licences - and not on a separate site, which is what a
 * private product has to do.
 *
 * The app's footer links to that file. A link that points at a file that is
 * not there, or at a policy that says something else than the app does, is
 * worse than no link at all, so the two are checked against each other here:
 * the policy has to exist in the repository, and the URL the app hands to the
 * browser has to name that exact file.
 *
 * This is a JVM test on purpose: it reads the source tree, and the source
 * tree is where the policy lives.
 */
class PrivacyPolicyTest {

    @Test
    fun thePolicyIsInTheRepository() {
        val policy = privacyPolicyFile()
        assertTrue(
            "the privacy policy should be in the repository, at docs/PRIVACY.md",
            policy.isFile
        )

        val text = policy.readText()

        assertTrue(
            "the policy should say that the app collects no data",
            text.contains("does not collect, transmit, share or sell any data")
        )
        assertTrue(
            "the policy should name the package the policy is about",
            text.contains("com.whereq.dosbox")
        )
        assertTrue(
            "the policy should point at the source code of the app",
            text.contains("https://github.com/whereq/whereq-dosbox")
        )
    }

    @Test
    fun theAppLinksToThePolicyInTheRepository() {
        val url = privacyPolicyUrl()

        assertTrue(
            "the privacy policy link should point at the repository, but it is \"$url\"",
            url.startsWith("https://github.com/whereq/whereq-dosbox/")
        )
        assertTrue(
            "the privacy policy link should name the policy file, but it is \"$url\"",
            url.endsWith("/docs/PRIVACY.md")
        )
    }

    @Test
    fun theReadmeLinksToThePolicy() {
        val readme = findInRepository("README.md")
        assertTrue("the README should be in the repository", readme.isFile)
        assertTrue(
            "the README should link to the privacy policy",
            readme.readText().contains("docs/PRIVACY.md")
        )
    }

    /* ------------------------------------------------------------------ */

    /**
     * The URL the launcher footer hands to the browser.
     *
     * It is read out of the string resource rather than out of the code, so
     * the test asks the same question the app does: what is in
     * `strings.xml`?
     */
    private fun privacyPolicyUrl(): String {
        val strings = findInRepository("android/app/src/main/res/values/strings.xml")
        assertTrue("the string resources should be in the repository", strings.isFile)
        val text = strings.readText()
        val match = Regex("""<string name="privacy_policy_url"[^>]*>([^<]+)</string>""")
            .find(text)
        assertTrue(
            "strings.xml should carry a privacy_policy_url",
            match != null
        )
        return match!!.groupValues[1]
    }

    /** The privacy policy file in the repository. */
    private fun privacyPolicyFile(): File = findInRepository("docs/PRIVACY.md")

    /**
     * Finds a file in the repository.
     *
     * The repository root is the directory that holds `COPYING` and `VERSION`
     * - the licence and the version file only exist there, so the lookup
     * cannot land on one of the sub-directories that have a README of their
     * own. The unit test's working directory is the module directory or the
     * repository root, depending on how Gradle was started, so the search
     * walks up from wherever it is.
     */
    private fun findInRepository(relative: String): File = File(repositoryRoot(), relative)

    /** The root of the repository. */
    private fun repositoryRoot(): File {
        val roots = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        for (root in roots) {
            if (File(root, "COPYING").isFile && File(root, "VERSION").isFile) {
                return root
            }
        }
        throw AssertionError(
            "could not find the repository root below " + System.getProperty("user.dir")
        )
    }
}
