package org.jabref.logic.git;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.jabref.gui.DialogService;
import org.jabref.gui.git.GitCredentialsDialogView;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.io.FileUtil;

import com.airhacks.afterburner.injection.Injector;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles the updating of the local and remote git repository that is located at the repository path
 * This provides an easy-to-use interface to manage a git repository
 */
public class GitHandler {
    static final Logger LOGGER = LoggerFactory.getLogger(GitHandler.class);
    final Path repositoryPath;
    final File repositoryPathAsFile;
    String gitUsername = Optional.ofNullable(System.getenv("GIT_EMAIL")).orElse("");
    String gitPassword = Optional.ofNullable(System.getenv("GIT_PW")).orElse("");
    final CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(gitUsername, gitPassword);
    private DialogService dialogService;

    /**
     * Initialize the handler for the given repository
     *
     * @param repositoryPath The root of the initialized git repository
     */
    public GitHandler(Path repositoryPath) {
        this.repositoryPath = repositoryPath;
        this.repositoryPathAsFile = this.repositoryPath.toFile();
        this.dialogService = Injector.instantiateModelOrService(DialogService.class);
        if (!isGitRepository()) {
            try {
                Git.init()
                   .setDirectory(repositoryPathAsFile)
                   .setInitialBranch("main")
                   .call();
                setupGitIgnore();
                String initialCommit = "Initial commit";
                if (!createCommitOnCurrentBranch(initialCommit, false)) {
                    // Maybe, setupGitIgnore failed and did not add something
                    // Then, we create an empty commit
                    try (Git git = Git.open(repositoryPathAsFile)) {
                        git.commit()
                           .setAllowEmpty(true)
                           .setMessage(initialCommit)
                           .call();
                    }
                }
            } catch (GitAPIException | IOException e) {
                LOGGER.error("Initialization failed");
            }
        }
    }

    void setupGitIgnore() {
        try {
            Path gitignore = Path.of(repositoryPath.toString(), ".gitignore");
            if (!Files.exists(gitignore)) {
                FileUtil.copyFile(Path.of(this.getClass().getResource("git.gitignore").toURI()), gitignore, false);
            }
        } catch (URISyntaxException e) {
            LOGGER.error("Error occurred during copying of the gitignore file into the git repository.", e);
        }
    }

    /**
     * Returns true if the given path points to a directory that is a git repository (contains a .git folder)
     */
    boolean isGitRepository() {
        // For some reason the solution from https://www.eclipse.org/lists/jgit-dev/msg01892.html does not work
        // This solution is quite simple but might not work in special cases, for us it should suffice.
        Path gitFolderPath = Path.of(repositoryPath.toString(), ".git");
        return Files.exists(gitFolderPath) && Files.isDirectory(gitFolderPath);
    }

    /**
     * Checkout the branch with the specified name, if it does not exist create it
     *
     * @param branchToCheckout Name of the branch to check out
     */
    public void checkoutBranch(String branchToCheckout) throws IOException, GitAPIException {
        try (Git git = Git.open(this.repositoryPathAsFile)) {
            Optional<Ref> branch = getRefForBranch(branchToCheckout);
            git.checkout()
               // If the branch does not exist, create it
               .setCreateBranch(branch.isEmpty())
               .setName(branchToCheckout)
               .call();
        }
    }

    /**
     * Returns the reference of the specified branch
     * If it does not exist returns an empty optional
     */
    Optional<Ref> getRefForBranch(String branchName) throws GitAPIException, IOException {
        try (Git git = Git.open(this.repositoryPathAsFile)) {
            return git.branchList()
                      .call()
                      .stream()
                      .filter(ref -> ref.getName().equals("refs/heads/" + branchName))
                      .findAny();
        }
    }

    /**
     * Creates a commit on the currently checked out branch
     *
     * @param amend Whether to amend to the last commit (true), or not (false)
     * @return Returns true if a new commit was created. This is the case if the repository was not clean on method invocation
     */
    public boolean createCommitOnCurrentBranch(String commitMessage, boolean amend) throws IOException, GitAPIException {
        boolean commitCreated = false;
        try (Git git = Git.open(this.repositoryPathAsFile)) {
            Status status = git.status().call();
            if (!status.isClean()) {
                commitCreated = true;
                // Add new and changed files to index
                git.add()
                   .addFilepattern(".")
                   .call();
                // Add all removed files to index
                if (!status.getMissing().isEmpty()) {
                    RmCommand removeCommand = git.rm()
                                                 .setCached(true);
                    status.getMissing().forEach(removeCommand::addFilepattern);
                    removeCommand.call();
                }
                git.commit()
                   .setAmend(amend)
                   .setAllowEmpty(false)
                   .setMessage(commitMessage)
                   .call();
            }
        }
        return commitCreated;
    }

    /**
     * Creates a commit on the currently checked out branch with a single file
     *
     * @param filename The name of the file to commit
     * @param amend Whether to amend to the last commit (true), or not (false)
     * @return Returns true if a new commit was created. This is the case if the repository was not clean on method invocation
     * @throws IOException
     * @throws GitAPIException
     * @throws NoWorkTreeException
     */
    public boolean createCommitWithSingleFileOnCurrentBranch(String filename, String commitMessage, boolean amend) throws IOException, NoWorkTreeException, GitAPIException {
        boolean commitCreated = false;
        
        Git git = Git.open(this.repositoryPathAsFile);
        Status status = git.status().call();
        if (!status.isClean()) {
            commitCreated = true;
  
            // Add new and changed files to index
            git.add()
                .addFilepattern(filename)
                .call();

            // Add all removed files to index
            if (!status.getMissing().isEmpty()) {
                RmCommand removeCommand = git.rm()
                                                .setCached(true);
                                                System.out.println("1----");
                status.getMissing().forEach(removeCommand::addFilepattern);
                removeCommand.call();
            }
            git.commit()
                .setAmend(amend)
                .setAllowEmpty(false)
                .setMessage(commitMessage)
                .call();
        }
        
        return commitCreated;
    }

    /**
     * Merges the source branch into the target branch
     *
     * @param targetBranch the name of the branch that is merged into
     * @param sourceBranch the name of the branch that gets merged
     */
    public void mergeBranches(String targetBranch, String sourceBranch, MergeStrategy mergeStrategy) throws IOException, GitAPIException {
        String currentBranch = this.getCurrentlyCheckedOutBranch();
        try (Git git = Git.open(this.repositoryPathAsFile)) {
            Optional<Ref> sourceBranchRef = getRefForBranch(sourceBranch);
            if (sourceBranchRef.isEmpty()) {
                // Do nothing
                return;
            }
            this.checkoutBranch(targetBranch);
            git.merge()
               .include(sourceBranchRef.get())
               .setStrategy(mergeStrategy)
               .setMessage("Merge search branch into working branch.")
               .call();
        }
        this.checkoutBranch(currentBranch);
    }

    /**
     * Pushes all commits made to the branch that is tracked by the currently checked out branch.
     * If pushing to remote fails, it fails silently.
     */
    public void pushCommitsToRemoteRepository() {
        try {
            Git git = Git.open(this.repositoryPathAsFile);
            String remoteURL = git.getRepository().getConfig().getString("remote", "origin", "url");
            Boolean isSshRemoteRepository = remoteURL != null ? remoteURL.contains("git@") : false;

            git.verifySignature();

            if (isSshRemoteRepository) {
                TransportConfigCallback transportConfigCallback = new SshTransportConfigCallback();
                git.push()
               .setTransportConfigCallback(transportConfigCallback)
               .call();
            } else if (this.gitPassword.equals("") || this.gitUsername.equals("")) {
                    GitCredentialsDialogView gitCredentialsDialogView = new GitCredentialsDialogView();

                    gitCredentialsDialogView.showGitCredentialsDialog();

                    CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(
                        gitCredentialsDialogView.getGitUsername(),
                        gitCredentialsDialogView.getGitPassword()
                    );

                    git.push()
                    .setCredentialsProvider(credentialsProvider)
                    .call();
                } else {
                    git.push()
                    .setCredentialsProvider(this.credentialsProvider)
                    .call();
                }
            } catch (IOException | GitAPIException e) {
            if (e.getMessage().equals("origin: not found.")) {
                LOGGER.info("No remote repository detected. Push skiped.");
            } else {
                LOGGER.info("Failed to push");
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Pulls all commits made to the branch that is tracked by the currently checked out branch.
     * If pulling to remote fails, it fails silently.
     */
    public void pullOnCurrentBranch() {
        try (Git git = Git.open(this.repositoryPathAsFile)) {
            try {
                git.pull()
                   .call();
            } catch (GitAPIException e) {
                if (e.getMessage().equals("origin: not found")) {
                    LOGGER.info("No remote repository detected. Push skiped.");
                } else {
                    LOGGER.info("Failed to pull");
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException ex) {
            LOGGER.info("Failed pulling git repository");
        }
    }

    /**
     * Get the short name of the current branch that HEAD points to.
     * If checking out fails, it fails silently.
     */
    public String getCurrentlyCheckedOutBranch() {
        try (Git git = Git.open(this.repositoryPathAsFile)) {
            return git.getRepository().getBranch();
        } catch (IOException ex) {
            LOGGER.info("Failed get current branch");
            return "";
        }
    }
}
