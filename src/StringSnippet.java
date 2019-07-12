import com.googlecode.d2j.node.DexMethodNode;
import com.googlecode.d2j.node.insn.ConstStmtNode;
import com.googlecode.d2j.node.insn.DexStmtNode;
import com.googlecode.d2j.node.insn.Stmt1RNode;
import com.googlecode.d2j.reader.Op;
import javafx.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class StringSnippet {
    public final File file;
    public DexMethodNode methodNode;
    public ConstStmtNode stringInitNode;
    public List<DexStmtNode> statements;
    public ArrayList<String> stringStatements = new ArrayList<>();
    public HashMap<Pair<Integer, Integer>, Integer> frequencyMap = new HashMap<>();
    public int stringResultRegister = 0;

    public StringSnippet(File file, DexMethodNode methodNode, ConstStmtNode stringInitNode) {
        this.file = file;
        this.statements = new ArrayList<>();
        this.methodNode = methodNode;
        this.stringInitNode = stringInitNode;
    }

    public String getString() {
        return this.stringInitNode.value.toString();
    }

    public double[] toVector() {
        double[] vector = new double[255 * 255];
        for(Pair<Integer, Integer> key : frequencyMap.keySet()) {
            int code = key.getKey() * 255 + key.getValue();
            vector[code] = frequencyMap.get(key);
        }
        return vector;
    }

    private int normalizeOpCode(int opcode) {
        if(opcode == Op.CONST_4.opcode) { opcode = Op.CONST_16.opcode; }
        if(opcode == Op.CONST_STRING_JUMBO.opcode) { opcode = Op.CONST_STRING.opcode; }
        return opcode;
    }

    private boolean prune(RegisterDependencyGraph graph) {
        // prune nodes that do not "contribute" towards the end result.
        // first, we do a BFS, starting from the last node, and mark visited nodes.
        // TODO we assume the last statement is a move-result(-object)

        DexStmtNode lastStmtNode = statements.get(statements.size() - 1);
        if(lastStmtNode.op != Op.MOVE_RESULT_OBJECT) {
            throw new RuntimeException("Pruning: last node not move-result-object!");
        }

        Stmt1RNode moveStmtNode = (Stmt1RNode) lastStmtNode;
        RegisterDependencyNode rootNode = graph.activeRegister.get(moveStmtNode.a);

        List<RegisterDependencyNode> visited = new ArrayList<>();
        LinkedList<RegisterDependencyNode> queue = new LinkedList<>();
        visited.add(rootNode);
        queue.add(rootNode);

        while(!queue.isEmpty()) {
            RegisterDependencyNode currentNode = queue.remove();
            List<RegisterDependencyNode> adjacent = graph.adjacency.get(currentNode);

            if(adjacent == null) {
                // it seems we are missing variables
                // TODO: assume that the string is not encrypted!
                return false;
            }

            for(RegisterDependencyNode adjacentNode : adjacent) {
                if(!visited.contains(adjacentNode)) {
                    visited.add(adjacentNode);
                    queue.add(adjacentNode);
                }
            }
        }

        // check whether the original string declaration is visited. If not, this string is probably not encrypted
        RegisterDependencyNode stringNode = new RegisterDependencyNode(stringInitNode.a, 1);
        if(!visited.contains(stringNode)) {
            return false;
        }

        ArrayList<DexStmtNode> toRemove = new ArrayList<>();
        for(RegisterDependencyNode graphNode : graph.adjacency.keySet()) {
            if(!visited.contains(graphNode)) {
                // remove all statements related to this node
                for(int i = 0; i < statements.size(); i++) {
                    if(graph.statementToRegister[i] == graphNode) {
                        toRemove.add(statements.get(i));
                    }
                }
            }
        }

        statements.removeAll(toRemove);
        return true;
    }

    public boolean finalizeSnippet() {
        RegisterDependencyGraph graph = new RegisterDependencyGraph(this);
        graph.build();
        if(!prune(graph)) {
            return false;
        }

        for(int i = 0; i < statements.size(); i++) {
            DexStmtNode node = statements.get(i);
            if(node.op != null) {
                stringStatements.add(node.op.toString());
            }
            if(i != statements.size() - 1) {
                DexStmtNode nextNode = statements.get(i + 1);
                if(node.op != null && nextNode.op != null) {
                    Pair<Integer, Integer> pair = new Pair<>(normalizeOpCode(node.op.opcode), normalizeOpCode(nextNode.op.opcode));
                    if(!frequencyMap.containsKey(pair)) {
                        frequencyMap.put(pair, 0);
                    }
                    frequencyMap.put(pair, frequencyMap.get(pair) + 1);
                }

            }
        }
        return true;
    }
}
