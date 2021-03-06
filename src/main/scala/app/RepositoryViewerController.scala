package app

import util.Directory._
import util.Implicits._
import _root_.util.{ReferrerAuthenticator, JGitUtil, FileUtil}
import service._
import org.scalatra._
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib._
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.treewalk._

class RepositoryViewerController extends RepositoryViewerControllerBase 
  with RepositoryService with AccountService with ReferrerAuthenticator

/**
 * The repository viewer.
 */
trait RepositoryViewerControllerBase extends ControllerBase { 
  self: RepositoryService with AccountService with ReferrerAuthenticator =>

  /**
   * Returns converted HTML from Markdown for preview.
   */
  post("/:owner/:repository/_preview")(referrersOnly { repository =>
    contentType = "text/html"
    view.helpers.markdown(params("content"), repository,
      params("enableWikiLink").toBoolean,
      params("enableCommitLink").toBoolean,
      params("enableIssueLink").toBoolean)
  })

  /**
   * Displays the file list of the repository root and the default branch.
   */
  get("/:owner/:repository")(referrersOnly {
    fileList(_)
  })
  
  /**
   * Displays the file list of the repository root and the specified branch.
   */
  get("/:owner/:repository/tree/:id")(referrersOnly {
    fileList(_, params("id"))
  })
  
  /**
   * Displays the file list of the specified path and branch.
   */
  get("/:owner/:repository/tree/:id/*")(referrersOnly {
    fileList(_, params("id"), multiParams("splat").head)
  })
  
  /**
   * Displays the commit list of the specified branch.
   */
  get("/:owner/:repository/commits/:branch")(referrersOnly { repository =>
    val branchName = params("branch")
    val page       = params.getOrElse("page", "1").toInt

    JGitUtil.withGit(getRepositoryDir(repository.owner, repository.name)){ git =>
      val (logs, hasNext) = JGitUtil.getCommitLog(git, branchName, page, 30)

      repo.html.commits(Nil, branchName, repository, logs.splitWith{ (commit1, commit2) =>
        view.helpers.date(commit1.time) == view.helpers.date(commit2.time)
      }, page, hasNext)
    }
  })
  
  /**
   * Displays the commit list of the specified resource.
   */
  get("/:owner/:repository/commits/:branch/*")(referrersOnly { repository =>
    val branchName = params("branch")
    val path       = multiParams("splat").head //.replaceFirst("^tree/.+?/", "")
    val page       = params.getOrElse("page", "1").toInt

    JGitUtil.withGit(getRepositoryDir(repository.owner, repository.name)){ git =>
      val (logs, hasNext) = JGitUtil.getCommitLog(git, branchName, page, 30, path)

      repo.html.commits(path.split("/").toList, branchName, repository,
        logs.splitWith{ (commit1, commit2) =>
          view.helpers.date(commit1.time) == view.helpers.date(commit2.time)
        }, page, hasNext)
    }
  })

  /**
   * Displays the file content of the specified branch or commit.
   */
  get("/:owner/:repository/blob/:id/*")(referrersOnly { repository =>
    val id         = params("id") // branch name or commit id
    val raw        = params.get("raw").getOrElse("false").toBoolean
    val path       = multiParams("splat").head //.replaceFirst("^tree/.+?/", "")

    JGitUtil.withGit(getRepositoryDir(repository.owner, repository.name)){ git =>
      val revCommit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve(id))

      @scala.annotation.tailrec
      def getPathObjectId(path: String, walk: TreeWalk): ObjectId = walk.next match {
        case true if(walk.getPathString == path) => walk.getObjectId(0)
        case true => getPathObjectId(path, walk)
      }

      val treeWalk = new TreeWalk(git.getRepository)
      val objectId = try {
        treeWalk.addTree(revCommit.getTree)
        treeWalk.setRecursive(true)
        getPathObjectId(path, treeWalk)
      } finally {
        treeWalk.release
      }

      if(raw){
        // Download
        contentType = "application/octet-stream"
        JGitUtil.getContent(git, objectId, false).get
      } else {
        // Viewer
        val large  = FileUtil.isLarge(git.getRepository.getObjectDatabase.open(objectId).getSize)
        val viewer = if(FileUtil.isImage(path)) "image" else if(large) "large" else "other"
        val bytes  = if(viewer == "other") JGitUtil.getContent(git, objectId, false) else None

        val content = if(viewer == "other"){
          if(bytes.isDefined && FileUtil.isText(bytes.get)){
            // text
            JGitUtil.ContentInfo("text", bytes.map(new String(_, "UTF-8")))
          } else {
            // binary
            JGitUtil.ContentInfo("binary", None)
          }
        } else {
          // image or large
          JGitUtil.ContentInfo(viewer, None)
        }

        repo.html.blob(id, repository, path.split("/").toList, content, new JGitUtil.CommitInfo(revCommit))
      }
    }
  })
  
  /**
   * Displays details of the specified commit.
   */
  get("/:owner/:repository/commit/:id")(referrersOnly { repository =>
    val id = params("id")

    JGitUtil.withGit(getRepositoryDir(repository.owner, repository.name)){ git =>
      val revCommit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve(id))

      repo.html.commit(id, new JGitUtil.CommitInfo(revCommit),
        JGitUtil.getBranchesOfCommit(git, revCommit.getName), JGitUtil.getTagsOfCommit(git, revCommit.getName),
        repository, JGitUtil.getDiffs(git, id))
    }
  })
  
  /**
   * Displays tags.
   */
  get("/:owner/:repository/tags")(referrersOnly {
    repo.html.tags(_)
  })
  
  /**
   * Download repository contents as an archive.
   */
  get("/:owner/:repository/archive/:name")(referrersOnly { repository =>
    val name = params("name")
    
    if(name.endsWith(".zip")){
      val revision = name.replaceFirst("\\.zip$", "")
      val workDir = getDownloadWorkDir(repository.owner, repository.name, session.getId)
      if(workDir.exists){
        FileUtils.deleteDirectory(workDir)
      }
      workDir.mkdirs
      
      // clone the repository
      val cloneDir = new File(workDir, revision)
      JGitUtil.withGit(Git.cloneRepository
          .setURI(getRepositoryDir(repository.owner, repository.name).toURI.toString)
          .setDirectory(cloneDir)
          .call){ git =>
      
        // checkout the specified revision
        git.checkout.setName(revision).call
      }
      
      // remove .git
      FileUtils.deleteDirectory(new File(cloneDir, ".git"))
      
      // create zip file
      val zipFile = new File(workDir, (if(revision.length == 40) revision.substring(0, 10) else revision) + ".zip")
      FileUtil.createZipFile(zipFile, cloneDir)
      
      contentType = "application/octet-stream"
      zipFile
    } else {
      BadRequest
    }
  })
  
  /**
   * Provides HTML of the file list.
   * 
   * @param repository the repository information
   * @param revstr the branch name or commit id(optional)
   * @param path the directory path (optional)
   * @return HTML of the file list
   */
  private def fileList(repository: RepositoryService.RepositoryInfo, revstr: String = "", path: String = ".") = {
    getRepository(repository.owner, repository.name, baseUrl).map { repositoryInfo =>
      val revision = if(revstr.isEmpty){
        repositoryInfo.repository.defaultBranch
      } else {
        revstr
      }

      JGitUtil.withGit(getRepositoryDir(repositoryInfo.owner, repositoryInfo.name)){ git =>
      // get latest commit
        val revCommit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve(revision))

        val files = JGitUtil.getFileList(git, revision, path)

        // process README.md
        val readme = files.find(_.name == "README.md").map { file =>
          new String(JGitUtil.getContent(Git.open(getRepositoryDir(repositoryInfo.owner, repositoryInfo.name)), file.id, true).get, "UTF-8")
        }

        repo.html.files(
          // current branch
          revision,
          // repository
          repositoryInfo,
          // current path
          if(path == ".") Nil else path.split("/").toList,
          // latest commit
          new JGitUtil.CommitInfo(revCommit),
          // file list
          files,
          // readme
          readme
        )
      }
    } getOrElse NotFound
  }
  
}