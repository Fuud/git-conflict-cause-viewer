import Cool.GraphElementKind.BASE
import Cool.GraphElementKind.LEFT
import Cool.GraphElementKind.RIGHT
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.AbstractTreeIterator


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


        fun GraphElement.traversGraphUpToBase(consumer: (GraphElement) -> Unit) {
            consumer(this)
            this.parents
                    .filter { !it.isMergeBase }
                    .forEach { it.traversGraphUpToBase(consumer) }
        }

        val graphElementRepo = mutableMapOf<ObjectId, GraphElement>()

        val left = GraphElement(mergeHead, LEFT, graphElementRepo)
        val right = GraphElement(origHead, RIGHT, graphElementRepo)
        var all = setOf(left, right)

        while (all.find { !it.isMergeBase } != null) {
            all = all.flatMap { it.parents }.toSet()
        }

        println("File $conflictFile:")
        left.traversGraphUpToBase { graphElem ->
            val commit = graphElem.commit
            if (commit.affects(conflictFile)) {
                println("our: ${commit.id.abbreviate(6).name()} ${commit.revCommit.shortMessage}")
            }
        }

        right.traversGraphUpToBase { graphElem ->
            val commit = graphElem.commit
            if (commit.affects(conflictFile)) {
                println("their: ${commit.id.abbreviate(6).name()} ${commit.revCommit.shortMessage}")
            }
        }
        println("------------------")
    }

    //---
    private fun diff(tree1: AbstractTreeIterator, tree2: AbstractTreeIterator): List<DiffEntry> =
            git.diff()
                    .setOldTree(tree1)
                    .setNewTree(tree2)
                    .call()

    //------------

    inner class Commit(val id: ObjectId) {

        val parents: List<Commit>
            get() = revCommit.parents.map { Commit(it) }

        val revCommit: RevCommit
            get() = repository.parseCommit(id)

        val tree: AbstractTreeIterator
            get() = CanonicalTreeParser().apply { reset(repository.newObjectReader(), revCommit.tree) }

        fun diffTo(other: Commit) = diff(this.tree, other.tree)

        fun affects(file: String) = parents.any { parent -> diffTo(parent).any { it.newPath == file || it.oldPath == file } }

        override fun toString(): String {
            return "Commit(id=$id)"
        }


    }


    class GraphElement(val commit: Commit, var kind: GraphElementKind, val graphElementRepo: MutableMap<ObjectId, GraphElement>) {
        val isMergeBase = (kind == BASE)

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
}

