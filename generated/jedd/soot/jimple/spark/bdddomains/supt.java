package soot.jimple.spark.bdddomains;

import jedd.*;
import soot.*;
import soot.jimple.spark.pag.*;

public class supt extends Domain {
    public Numberer numberer() { return Scene.v().getTypeNumberer(); }
    
    public static Domain v() { return supt.instance; }
    
    private static Domain instance = new supt();
    
    public supt() { super(); }
}