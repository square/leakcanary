package shark

import java.io.Serializable
import shark.ObjectDominators.DominatorNode

class Dominators(val dominatorNodes: Map<Long, DominatorNode>) : Serializable
