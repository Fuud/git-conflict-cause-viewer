import Cool.GraphElementKind.BASE
import Cool.GraphElementKind.LEFT
import Cool.GraphElementKind.RIGHT
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter


fun main(args: Array<String>) {
    val builder = FileRepositoryBuilder()
//    val gitDir = "C:\\Users\\Public\\work\\test_git\\.git"
    val gitDir = ".git"
    val repository = builder.setGitDir(File(gitDir).absoluteFile)
            .readEnvironment() // scan environment GIT_* variables
            .findGitDir() // scan up the file system tree
            .build()

    Cool(repository).doTheJob()
}

class Cool(private val repository: Repository) {
    private val git = Git(repository)

    fun doTheJob() {
        val mergeHeads = repository.readMergeHeads()
        if (mergeHeads.size != 1) {
            throw IllegalStateException("Expected one merge head but got $mergeHeads")
        }

        val mergeHead = Commit(mergeHeads[0])
        val origHead = Commit(repository.readOrigHead())

        val conflicting = git.status().call().conflicting

        conflicting.forEach { conflictFile ->
            makeAffectedGraph(conflictFile, mergeHead, origHead)
        }
    }

    private fun makeAffectedGraph(conflictFile: String, mergeHead: Commit, origHead: Commit) {


        fun GraphElement.traversGraphUpToBase(visited:MutableSet<GraphElement> = hashSetOf(), consumer: (GraphElement) -> Unit) {
            consumer(this)
            visited.add(this)
            this.parents
                    .filter { !it.isMergeBase }
                    .filter { !visited.contains(it) }
                    .forEach { it.traversGraphUpToBase(visited = visited, consumer = consumer) }
        }

        val graphElementRepo = mutableMapOf<ObjectId, GraphElement>()

        val left = GraphElement(mergeHead, LEFT, graphElementRepo)
        val right = GraphElement(origHead, RIGHT, graphElementRepo)
        var all = setOf(left, right)

        while (all.find { !it.isMergeBase } != null) {
            all = all.flatMap { it.parents }.toSet()
        }

        val formatGraphElement = {graphElement: GraphElement ->
            val merge = if (graphElement.parents.size > 1){
                "[merge]"
            }else{
                ""
            }

            val message = graphElement.commit.revCommit.shortMessage
            val id = graphElement.commit.id.abbreviate(6).name()
            listOf(merge, id, message).joinToString(separator = " ")
        }

        println("File $conflictFile:")
        left.traversGraphUpToBase { graphElem ->
            val commit = graphElem.commit
            if (commit.affects(conflictFile)) {
                println("their: ${formatGraphElement(graphElem)}")
            }
        }

        right.traversGraphUpToBase { graphElem ->
            val commit = graphElem.commit
            if (commit.affects(conflictFile)) {
                println("our: ${formatGraphElement(graphElem)}")
            }
        }
        println("------------------")
    }

    //---
    private fun diff(tree1: AbstractTreeIterator, tree2: AbstractTreeIterator, file: String): List<DiffEntry> =
            git.diff()
                    .setOldTree(tree1)
                    .setNewTree(tree2)
                    .setShowNameAndStatusOnly(true)
                    .setPathFilter(PathFilter.create(file))
                    .call()

    //------------

    inner class Commit(val id: ObjectId) {

        val parents: List<Commit>
            get() = revCommit.parents.map { Commit(it) }

        val revCommit: RevCommit
            get() = repository.parseCommit(id)

        val tree: AbstractTreeIterator
            get() = CanonicalTreeParser().apply { reset(repository.newObjectReader(), revCommit.tree) }

        fun diffTo(other: Commit, file: String) = diff(this.tree, other.tree, file)

        fun affects(file: String) = parents.any { parent -> diffTo(parent, file).all { it.newPath == file || it.oldPath == file } }

        override fun toString(): String {
            return "Commit(id=$id)"
        }


    }


    class GraphElement(val commit: Commit, var kind: GraphElementKind, val graphElementRepo: MutableMap<ObjectId, GraphElement>) {
        val isMergeBase
            get() = (kind == BASE)

        var message = commit.revCommit.shortMessage
        private var p: List<GraphElement>? = null

        val parents: List<GraphElement>
            get() {
                if (p == null) {
                    p = this.commit.parents.map { getGraphElement(it) }
                    p!!.forEach { it.applyKind(kind) }
                }
                return p!!
            }

        private fun getGraphElement(commit: Commit): GraphElement = graphElementRepo.getOrPut(commit.id) { GraphElement(commit, kind, graphElementRepo) }

        private fun applyKind(kind: GraphElementKind) {
            if (this.kind == BASE || this.kind == kind){
                return
            }
            this.kind = BASE
            p?.forEach { it.applyKind(BASE) }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as GraphElement

            if (commit != other.commit) return false

            return true
        }

        override fun hashCode(): Int {
            return commit.hashCode()
        }


    }

    enum class GraphElementKind {
        LEFT,
        RIGHT,
        BASE
    }

    class CachedConfigFileRepository(gitDir: File) : FileRepository(gitDir){
        var cachedConfig: FileBasedConfig? = null

        override fun getConfig(): FileBasedConfig{
            if (cachedConfig==null){
                cachedConfig = super.getConfig()
            }
            return cachedConfig!!
        }
    }
}

