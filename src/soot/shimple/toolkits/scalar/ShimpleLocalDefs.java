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

package soot.shimple.toolkits.scalar;

import soot.*;
import soot.util.*;
import soot.shimple.*;
import soot.toolkits.scalar.*;
import java.util.*;

/**
 * This class implements the LocalDefs interface for Shimple.
 * ShimpleLocalDefs can be used in conjunction with SimpleLocalUses to
 * provide Definition/Use and Use/Definition chains in SSA.
 *
 * <p> This implementation can be considered a small demo for how SSA
 * can be put to good use. It is much simpler than
 * soot.toolkits.scalar.SimpleLocalDefs thanks to SSA form.  The
 * lesson to learn?  Shimple can be treated as Jimple with the added
 * benefits of SSA assumptions.
 *
 * <p> In addition to the interface required by LocalDefs,
 * ShimpleLocalDefs also provides a method for obtaining the
 * definition Unit given a Local.
 *
 * @author Navindra Umanee
 * @see soot.toolkits.scalar.SimpleLocalDefs
 * @see soot.toolkits.scalar.SimpleLocalUses
 **/
public class ShimpleLocalDefs implements LocalDefs
{
    protected  Map localToDefs;

    /**
     * Build a LocalDefs interface from a ShimpleBody.  Proper SSA
     * form is required, otherwise correct behaviour is not
     * guaranteed.
     **/
    public ShimpleLocalDefs(ShimpleBody sb)
    {
        // Instead of rebuilding the ShimpleBody without the
        // programmer's knowledge, throw a RuntimeException
        if(!sb.getIsSSA())
            throw new RuntimeException("ShimpleBody is not in proper SSA form as required by ShimpleLocalDefs.  You may need to rebuild it or use SimpleLocalDefs instead.");

        // build localToDefs map simply by iterating through all the
        // units in the body and saving the unique definition site for
        // each scalar local -- no need for fancy analysis 
        {
            Chain unitsChain = sb.getUnits();
            Iterator unitsIt = unitsChain.iterator();
            localToDefs = new HashMap(unitsChain.size() * 2 + 1, 0.7f);
        
            while(unitsIt.hasNext()){
                Unit unit = (Unit) unitsIt.next();
                Iterator defBoxesIt = unit.getDefBoxes().iterator();
                while(defBoxesIt.hasNext()){
                    Value value = ((ValueBox)defBoxesIt.next()).getValue();

                    // only map scalars
                    Type type = value.getType();

                    // only map Shimple-processed locals
                    if(!(type instanceof PrimType)){
                        if(sb.isScalarsOnly())
                            continue;
                        else if(!(type instanceof RefType))
                            continue;
                    }
                        
                    localToDefs.put(value, new SingletonList(unit));
                }
            }
        }
    }

    /**
     * Unconditionally returns the definition site of a local and
     * EMPTY_LIST if local is not a scalar.
     *
     * <p> This method is currently not required by the LocalDefs
     * interface, but we return a singleton List instead of a Unit in
     * case LocalDefs requires such a method in the future.
     **/
    public List getDefsOf(Local l)
    {
        // only concerned with scalars; for non-scalars we return an
        // empty list
        {
            Type localType = l.getType();
            if(!(localType instanceof PrimType))
                return Collections.EMPTY_LIST;
        }

        List defs = (List) localToDefs.get(l);

        if(defs == null)
            throw new RuntimeException("Local not found in Body.");

        return defs;
    }
    
    /**
     * Implementation of LocalDefs interface for scalars.
     *
     * <p> It is assumed the programmer knows that ShimpleLocalDefs
     * only applies to non-scalars.  In an attempt to behave nicely
     * with some existing code (SimpleLocalUses), ShimpleLocalDefs
     * silently returns an empty list of definitions for non-scalar
     * uses.
     *
     * <p> If support for non-scalars is required, SimpleLocalDefs can
     * be used instead.
     **/
    public List getDefsOfAt(Local l, Unit s)
    {
        // For consistency with SimpleLocalDefs, check that the local
        // is indeed used in the given Unit.  This neatly sidesteps
        // the problem of checking whether the local is actually
        // defined at the given point in the program.
        {
            Iterator boxIt = s.getUseBoxes().iterator();
            boolean defined = false;

            while(boxIt.hasNext()){
                Value value = ((ValueBox) boxIt.next()).getValue();
                if(value.equals(l)){
                    defined = true;
                    break;
                }
            }

            if(!defined)
                throw new RuntimeException("Illegal LocalDefs query; local " + l + " is not being used at " + ((ToBriefString) s).toBriefString());
        }

        return getDefsOf(l);
    }
}