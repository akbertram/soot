/* Soot - a J*va Optimization Framework
 * Copyright (C) 2003 Navindra Umanee
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.shimple.internal;

import soot.*;
import soot.util.*;
import java.util.*;
import soot.shimple.*;
import soot.shimple.internal.analysis.*;
import soot.shimple.toolkits.scalar.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.base.*;
import soot.jimple.toolkits.scalar.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

/**
 * This class does the real high-level work.  It takes a Jimple body
 * or Jimple/Shimple hybrid body and produces pure Shimple.
 *
 * <p> The work is done in two main steps:
 *
 * <ol>
 * <li> Trivial PHI-functions are added.
 * <li> A renaming algorithm is executed.
 * </ol>
 *
 * <p> This class can also translate out of Shimple by producing an
 * equivalent Jimple body with all PHI-functions removed.
 *
 * <p> Note that this is an internal class, understanding it should
 * not be necessary from a user point-of-view and relying on it
 * directly is not recommended.
 *
 * @author Navindra Umanee
 * @see soot.shimple.ShimpleBody
 * @see <a
 * href="http://citeseer.nj.nec.com/cytron91efficiently.html">Efficiently
 * Computing Static Single Assignment Form and the Control Dependence
 * Graph</a>
 **/
public class ShimpleBodyBuilder
{
    protected ShimpleBody body;
    protected DominatorTree dt;
    protected BlockGraph cfg;

    /**
     * A fixed list of all original Locals that pertain to scalars.
     **/
    protected List origLocals;

    /**
     * An analysis class that allows us to verify that a variable is
     * guaranteed to be defined at program point P.  Used as an
     * accessory to tweaking our PHI-function insertion algorithm.
     **/
    protected GuaranteedDefs gd;

    /**
     * Transforms the provided body to pure SSA form.
     **/
    public ShimpleBodyBuilder(ShimpleBody body, boolean hasPhiNodes)
    {
        this.body = body;

        // our SSA building algorithm currently assumes there are no
        // foreign PHI nodes in the given body, therefore, any such
	// PHI nodes must be eliminated first. The results may not 
	// be as pretty as expected and it's possible that as part of 
	// the process of eliminating and subsequent recomputation of 
	// SSA form, minimality (to the original source) is not 
	// maintained.
        if(hasPhiNodes)
            eliminatePhiNodes(body);
        
        cfg = new CompleteBlockGraph(body);
        dt = new DominatorTree(cfg, true);
        gd = new GuaranteedDefs(new CompleteUnitGraph(body));
        origLocals = new ArrayList();

        Iterator localsIt = cfg.getBody().getLocals().iterator();

        while(localsIt.hasNext()){
            Local local = (Local) localsIt.next();

            Type localType = local.getType();
            
            // only concerned with scalars
            if(localType instanceof PrimType)
                origLocals.add(local);

            if(!body.isScalarsOnly() && localType instanceof RefType)
                origLocals.add(local);
        }

        /* Carry out the transformations. */
        
        insertTrivialPhiNodes();
        renameLocals();
        trimExceptionalPhiNodes();
    }

    /**
     * PHI-function Insertion Algorithm from Cytron et al 91, P24-5,
     * implemented in various bits and pieces by the next functions.
     *
     * <p> For each definition of variable V, find the iterated
     * dominance frontier.  Each block in the iterated dominance
     * frontier is prepended with a trivial PHI-function.
     *
     * <p> We found out the hard way that this isn't the ideal
     * solution for Jimple because a lot of redundant PHI functions
     * get inserted probably due to the fact that the algorithm
     * assumes all variables have an initial definition on entry.
     *
     * <p> While this assumption does not produce incorrect results, it
     * produces hopelessly complicated and ineffectual code.
     *
     * <p> Our quick solution was to ensure that a variable was
     * defined along all paths to the block where we were considering
     * insertion of a PHI-function.  If the variable was not defined
     * along at least one path (isLocalDefinedOnEntry()), then
     * certainly a PHI-function was superfluous and meaningless.  Our
     * GuaranteedDefs flow analysis provided us with the necessary
     * information.
     *
     * <p> Better and more efficient alternatives suggest themselves.
     * We later found this formulation from IBM's Jikes RVM:
     *
     * <p><i> Special Java case: if node N dominates all defs of V,
     * then N does not need a PHI-function for V. </i>
     **/
    public void insertTrivialPhiNodes()
    {
        Map localsToDefPoints = new HashMap();

        // compute localsToDefPoints
        // ** can we use LocalDefs instead?  don't think so.
        {
            Iterator localsIt = origLocals.iterator();
            
            while(localsIt.hasNext()){
                Local local = (Local)localsIt.next();

                // all blocks containing definitions of our Local
                List blockList = new ArrayList();

                Iterator blocksIt = cfg.iterator();

                while(blocksIt.hasNext()){
                    Block block = (Block)blocksIt.next();
                
                    Iterator defBoxesIt = getDefBoxesFromBlock(block).iterator();

                    while(defBoxesIt.hasNext()){
                        Value def = ((ValueBox)defBoxesIt.next()).getValue();

                        // ** equals or equivTo?  equals appears to
                        // ** be correct for Locals in particular
                        if(def.equals(local)){
                            blockList.add(block);
                            break;
                        }
                    }
                }
                
                localsToDefPoints.put(local, blockList);
            }
        }

        /* Routine initialisations. */
        
        int[] workFlags = new int[cfg.size()];
        int[] hasAlreadyFlags = new int[cfg.size()];
        
        int iterCount = 0;
        Stack workList = new Stack();

        /* Main Cytron algorithm. */
        
        {
            Iterator localsIt = localsToDefPoints.keySet().iterator();

            while(localsIt.hasNext()){
                Local local = (Local) localsIt.next();

                iterCount++;

                // initialise worklist
                {
                    Iterator defNodesIt = ((List) localsToDefPoints.get(local)).iterator();
                    while(defNodesIt.hasNext()){
                        Block block = (Block) defNodesIt.next();
                        workFlags[block.getIndexInMethod()] = iterCount;
                        workList.push(block);
                    }
                }

                while(!workList.empty()){
                    Block block = (Block) workList.pop();
                    
                    DominatorNode node = dt.fetchNode(block);

                    Iterator frontierNodes = node.getDominanceFrontier().iterator();

                    while(frontierNodes.hasNext()){
                        Block frontierBlock = ((DominatorNode) frontierNodes.next()).getBlock();
                        int fBIndex = frontierBlock.getIndexInMethod();
                        
                        if(hasAlreadyFlags[fBIndex] < iterCount){

                            // Make sure we don't add useless PHI-functions
                            if(isLocalDefinedOnEntry(local, frontierBlock))
                                prependTrivialPhiNode(local, frontierBlock);

                            hasAlreadyFlags[fBIndex] = iterCount;

                            if(workFlags[fBIndex] < iterCount){
                                workFlags[fBIndex] = iterCount;

                                workList.push(frontierBlock);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Inserts a trivial PHI-function with the appropriate number of
     * arguments.
     **/
    public void prependTrivialPhiNode(Local local, Block frontierBlock)
    {
        List preds = frontierBlock.getPreds();

        Unit trivialPhi = Jimple.v().newAssignStmt(local, Shimple.v().newPhiExpr(local, preds));

        Unit blockHead = frontierBlock.getHead();

        // is it a catch block?
        if(blockHead instanceof IdentityUnit)
            frontierBlock.insertAfter(trivialPhi, frontierBlock.getHead());
        else
            frontierBlock.insertBefore(trivialPhi, frontierBlock.getHead());
    }

    /**
     * Function that allows us to weed out special cases where
     * we do not require PHI-functions.
     *
     * <p> Temporary implementation, with much room for improvement.
     **/
    protected boolean isLocalDefinedOnEntry(Local local, Block block)
    {
        Iterator unitsIt = block.iterator();

        Unit unit = (Unit) unitsIt.next();
        
        // ***
        // if(unit.equals(((Trap)body.getTraps().getFirst()).getHandlerUnit()))
        // return false;
        // else
        // System.out.println(body.getTraps());
        
        // this will return null if the head unit is an inserted PHI statement
        List definedLocals = gd.getGuaranteedDefs(unit);

        // *** TODO: Verify that this will never fail.
        while(definedLocals == null){
            unit = (Unit) unitsIt.next();
            definedLocals = gd.getGuaranteedDefs(unit);
        }
        
        return definedLocals.contains(local);
    }
    
    /**
     * Maps new name Strings to Locals.
     **/
    protected Map newLocals;

    /**
     * Maps renamed Local's to original Local's.
     **/
    protected Map newLocalsToOldLocal;

    protected int[] assignmentCounters;
    protected Stack[] namingStacks;
    
    /**
     * Variable Renaming Algorithm from Cytron et al 91, P26-8,
     * implemented in various bits and pieces by the next functions.
     * Must be called after trivial PHI-functions have been added.
     *
     * <pre>
     *  call search(entry)
     *
     *  search(X):
     *  for each statement A in X do
     *     if A is an ordinary assignment
     *       for each variable V used do
     *            replace use of V by use of V_i where i = Top(S(V))
     *       done
     *     fi
     *    for each V in LHS(A) do
     *       i <- C(V)
     *       replace V by new V_i in LHS(A)
     *       push i onto S(V)
     *       C(V) <- i+1
     *    done
     *  done (end of first loop)
     *  for each Y in succ(X) do
     *      j <- whichPred(Y, X)
     *      for each PHI-function F in Y do
     *       replace the j-th operand V in RHS(F) by V_i with i = TOP(S(V))
     *     done
     *  done (end of second loop)
     *  for each Y in Children(X) do
     *    call search(Y)
     *  done (end of third loop)
     *  for each assignment A in X do
     *     for each V in oldLHS(A) do
     *       pop(S(V))
     *    done
     *  done (end of fourth loop)
     *  end
     * <pre>
     **/
    public void renameLocals()
    {
        newLocals = new HashMap();
        newLocalsToOldLocal = new HashMap();
        
        assignmentCounters = new int[origLocals.size()];
        namingStacks = new Stack[origLocals.size()];

        for(int i = 0; i < namingStacks.length; i++)
            namingStacks[i] = new Stack();

        List heads = cfg.getHeads();

        if(heads.size() == 0)
            return;

        // There should always be a single entry point in a
        // CompleteBlockGraph: no exception entry points, etc.
        // Right?
        if(heads.size() != 1)
            throw new RuntimeException("Dazed and confused.");
        
        Block entry = (Block) heads.get(0);
        
        renameLocalsSearch(entry);
    }

    /**
     * Driven by renameLocals().
     **/
    public void renameLocalsSearch(Block block)
    {
        // accumulated in Step 1 to be re-processed in Step 4
        List lhsLocals = new ArrayList();
        
        // Step 1 of 4 -- Rename block's uses (ordinary) and defs
        {
            // accumulated and re-processed in a later loop
            Iterator unitsIt = block.iterator();

            while(unitsIt.hasNext()){
                Unit unit = (Unit) unitsIt.next();

                // Step 1/2 of 1
                {
                    List useBoxes = new ArrayList();

                    // process all Ordinary Uses
                    if(unit instanceof AssignStmt){
                        Value rValue = ((AssignStmt) unit).getRightOp();
                        
                        if(!(rValue instanceof PhiExpr))
                            useBoxes.addAll(unit.getUseBoxes());
                    }
                    else{
                        useBoxes.addAll(unit.getUseBoxes());
                    }

                    Iterator useBoxesIt = useBoxes.iterator();
                
                    while(useBoxesIt.hasNext()){
                        ValueBox useBox = (ValueBox) useBoxesIt.next();
                        Value use = useBox.getValue();

                        int localIndex = indexOfLocal(use);
                    
                        // skip everything but our valid scalar Locals 
                        if(localIndex == -1)
                            continue;

                        Local localUse = (Local) use;

                        if(namingStacks[localIndex].empty())
                            continue;

                        Integer subscript = (Integer) namingStacks[localIndex].peek();

                        Local renamedLocal = fetchNewLocal(localUse, subscript);
                        useBox.setValue(renamedLocal);
                    }
                }

                // Step 1 of 1
                {
                    if(!(unit instanceof AssignStmt))
                        continue;
                
                    AssignStmt assignStmt = (AssignStmt) unit;
                    
                    Value lhsValue = assignStmt.getLeftOp();
                
                    // make sure we're dealing with a scalar assignment
                    if(!origLocals.contains(lhsValue))
                        continue;

                    ValueBox lhsLocalBox = assignStmt.getLeftOpBox();
                    Local lhsLocal = (Local) lhsValue;

                    // re-processed in Step 4
                    lhsLocals.add(lhsLocal);

                    int localIndex = indexOfLocal(lhsLocal);
                    if(localIndex == -1)
                        throw new RuntimeException("Dazed and confused.");
                
                    Integer subscript = new Integer(assignmentCounters[localIndex]);

                    Local newLhsLocal = fetchNewLocal(lhsLocal, subscript);
                    lhsLocalBox.setValue(newLhsLocal);

                    namingStacks[localIndex].push(subscript);
                    assignmentCounters[localIndex]++;                    
                    
                }
            }
        }

        // Step 2 of 4 -- Rename PHI-function uses in Successors
        {
            Iterator succsIt = block.getSuccs().iterator();

            while(succsIt.hasNext()){
                Block succ = (Block) succsIt.next();

                Iterator unitsIt = succ.iterator();

                while(unitsIt.hasNext()){
                    Unit unit = (Unit) unitsIt.next();

                    if(!(unit instanceof AssignStmt))
                        continue;

                    AssignStmt assignStmt = (AssignStmt) unit;

                    Value rhsRValue = assignStmt.getRightOp();

                    // only interested in PHI expressions
                    if(!(rhsRValue instanceof PhiExpr))
                        continue;

                    PhiExpr phiExpr = (PhiExpr) rhsRValue;

                    // simulate whichPred
                    int argIndex = phiExpr.getArgIndex(block);
                    if(argIndex == -1)
                        throw new RuntimeException("Dazed and confused.");
                        
                    ValueBox phiArgBox = phiExpr.getArgBox(argIndex);

                    Local phiArg = (Local) phiArgBox.getValue();
                    
                    int localIndex = indexOfLocal(phiArg);
                    if(localIndex == -1)
                        throw new RuntimeException("Dazed and confused.");
                    
                    if(namingStacks[localIndex].empty())
                        continue;

                    Integer subscript = (Integer) namingStacks[localIndex].peek();
                    
                    Local newPhiArg = fetchNewLocal(phiArg, subscript);
                    phiArgBox.setValue(newPhiArg);
                }
            }
        }

        // Step 3 of 4 -- Recurse over children.
        {
            DominatorNode node = dt.fetchNode(block);

            // now we recurse over children

            Iterator childrenIt = node.getChildren().iterator();

            while(childrenIt.hasNext()){
                DominatorNode childNode = (DominatorNode) childrenIt.next();

                renameLocalsSearch(childNode.getBlock());
            }
        }

        // Step 4 of 4 -- Tricky name stack updates.
        {
            Iterator lhsLocalsIt = lhsLocals.iterator();

            while(lhsLocalsIt.hasNext()){
                Local lhsLocal = (Local) lhsLocalsIt.next();

                int lhsLocalIndex = indexOfLocal(lhsLocal);
                if(lhsLocalIndex == -1)
                    throw new RuntimeException("Dazed and confused.");
                
                namingStacks[lhsLocalIndex].pop();
            }
        }

        /* And we're done.  The renaming process is complete. */
    }

    /**
     * Clever convenience function to fetch or create new Local's
     * given a Local and the desired subscript.
     **/
    protected Local fetchNewLocal(Local local, Integer subscript)
    {
        Local oldLocal = local;
        
        if(!origLocals.contains(local))
            oldLocal = (Local) newLocalsToOldLocal.get(local);
        
        if(subscript.intValue() == 0)
            return oldLocal;


        // ** TODO: What if this name already exists?
        // In theory it doesn't matter since we are creating a new Local
        // object.  The text output can be wrong, however.
        String name = oldLocal.getName() + "_" + subscript;

        Local newLocal = (Local) newLocals.get(name);

        if(newLocal == null){
            newLocal = new JimpleLocal(name, oldLocal.getType());

            newLocals.put(name, newLocal);
            newLocalsToOldLocal.put(newLocal, oldLocal);

            // add proper Local declation
            cfg.getBody().getLocals().add(newLocal);
        }

        return newLocal;
    }

    /**
     * Clever convenience function to fetch proper array indexes into
     * our naming arrays.
     **/
    protected int indexOfLocal(Value local)
    {
        int localIndex = origLocals.indexOf(local);

        if(localIndex == -1){
            // might be null
            Local oldLocal = (Local) newLocalsToOldLocal.get(local);

            localIndex = origLocals.indexOf(oldLocal);
        }
        
        return localIndex;
    }

    /**
     * Remove PHI-functions from current body, high probablity this destroys
     * SSA form.
     *
     * <p> Dead code elimination + register aggregation are performed
     * as recommended by Cytron.  The Aggregator looks like it could
     * use some improvements.  Skipped if "-p shimple
     * naive-phi-elimination" is specified.
     **/
    public static void eliminatePhiNodes(ShimpleBody body)
    {
        ShimpleOptions options = body.getOptions();
        
        // off by default
        if(options.pre_optimize_phi_elimination() && !options.naive_phi_elimination())
        {
            DeadAssignmentEliminator.v().transform(body);
            Aggregator.v().transform(body);
        }

        // offloaded in a separate function for historical reasons
        eliminatePhiNodes(body.getUnits());

        // on by default
        if(options.post_optimize_phi_elimination() && !options.naive_phi_elimination())
        {
            DeadAssignmentEliminator.v().transform(body);
            Aggregator.v().transform(body);
        }
    }
    
    /**
     * Eliminate PHI-functions in block by naively replacing then with
     * shimple assignment statements in the control flow predecessors.
     **/
    public static void eliminatePhiNodes(Chain units)
    {
        List phiNodes = new ArrayList();
        Map stmtsToAppend = new HashMap();
        
        Iterator unitsIt = units.iterator();

        while(unitsIt.hasNext()){
            Unit unit = (Unit) unitsIt.next();

            PhiExpr phi = Shimple.getPhiExpr(unit);
            
            if(phi == null)
                continue;

            Local lhsLocal = Shimple.getLhsLocal(unit);

            for(int i = 0; i < phi.getArgCount(); i++){
                Value phiValueArg = phi.getValueArg(i);
                Unit pred = phi.getPredArg(i);
                
                AssignStmt convertedPhi = Jimple.v().newAssignStmt(lhsLocal, phiValueArg);
                stmtsToAppend.put(convertedPhi, pred);
            }

            phiNodes.add(unit);
        }

        /* Avoid Concurrent Modification exceptions. */

        Iterator stmtsIt = stmtsToAppend.keySet().iterator();

        while(stmtsIt.hasNext()){
            Unit stmt = (Unit) stmtsIt.next();
            Unit pred = (Unit) stmtsToAppend.get(stmt);
            
            // *** Do necessary priming here
            if(pred.branches())
                units.insertBefore(stmt, pred);
            else
                units.insertAfter(stmt, pred);
        }
        
        Iterator phiNodesIt = phiNodes.iterator();

        while(phiNodesIt.hasNext()){
            Unit removeMe = (Unit) phiNodesIt.next();
            units.remove(removeMe);
        }
    }

    public void trimExceptionalPhiNodes()
    {
        Set handlerUnits = new HashSet();
        Iterator trapsIt = body.getTraps().iterator();

        while(trapsIt.hasNext()) {
            Trap trap = (Trap) trapsIt.next();
            handlerUnits.add(trap.getHandlerUnit());
        }

        Iterator blocksIt = cfg.iterator();
        while(blocksIt.hasNext()){
            Block block = (Block) blocksIt.next();

            // trim relevant Phi expressions
            if(handlerUnits.contains(block.getHead())){
                Iterator unitsIt = block.iterator();
                while(unitsIt.hasNext()){
                    Unit unit = (Unit) unitsIt.next();
                    PhiExpr phi = Shimple.getPhiExpr(unit);
                    if(phi == null)
                        continue;

                    trimExceptionalPhiNode(phi);
                }
            }
        }
    }

    public void trimExceptionalPhiNode(PhiExpr phiExpr)
    {
        Chain units = body.getUnits();
        Value currentArg = null;
        Unit previousUnit = null;

        Iterator argsIt = phiExpr.getArgs().iterator();

        while(argsIt.hasNext()){
            ValueUnitPair argBox = (ValueUnitPair) argsIt.next();
            Value value = argBox.getValue();
            Unit unit = argBox.getUnit();

            if(previousUnit == null){
                previousUnit = unit;
                currentArg = value;
                continue;
            }
            
            if(value.equals(currentArg)){
                if(previousUnit != units.getLast()){
                    Unit succ = (Unit) units.getSuccOf(previousUnit);

                    // unit follows previousUnit: hence same try block
                    if(succ.equals(unit)){
                        if(!phiExpr.removeArg(argBox))
                            throw new RuntimeException("Dazed and confused.");
                    }
                }
            }
            else{
                currentArg = value;
            }

            previousUnit = unit;
        }
    }
    
    /**
     * Convenience function that really ought to be implemented in
     * soot.toolkits.graph.Block.
     **/
    public List getDefBoxesFromBlock(Block block)
    {
        Iterator unitsIt = block.iterator();
        
        List defBoxesList = new ArrayList();
    
        while(unitsIt.hasNext())
            defBoxesList.addAll(((Unit)unitsIt.next()).getDefBoxes());
        
        return defBoxesList;
    }

    /**
     * Convenience function that really ought to be implemented in
     * soot.toolkits.graph.Block
     **/
    public List getUseBoxesFromBlock(Block block)
    {
        Iterator unitsIt = block.iterator();
        
        List useBoxesList = new ArrayList();
    
        while(unitsIt.hasNext())
            useBoxesList.addAll(((Unit)unitsIt.next()).getUseBoxes());
        
        return useBoxesList;
    }
}