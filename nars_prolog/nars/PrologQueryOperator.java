package nars;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import nars.core.Parameters;
import nars.entity.Task;
import nars.io.Symbols;
import nars.language.Inheritance;
import nars.language.Product;
import nars.language.Term;
import nars.operator.Operation;
import nars.operator.Operator;
import nars.prolog.Agent;
import nars.prolog.Int;
import nars.prolog.InvalidTheoryException;
import nars.prolog.NoSolutionException;
import nars.prolog.Prolog;
import nars.prolog.SolveInfo;
import nars.prolog.Struct;
import nars.prolog.Theory;
import nars.prolog.Var;
import nars.storage.Memory;

/**
 *
 * @author me
 */


public class PrologQueryOperator extends Operator {
    private final PrologContext context;

    
    
    public PrologQueryOperator(PrologContext context) {
        super("^prologQuery");
        this.context = context;
    }

    @Override
    protected List<Task> execute(Operation operation, Term[] args, Memory memory) {
        if (args.length == 0) {
            return null;
        }
        
        if (((args.length - 1) % 2) != 0) {
            return null;
        }
        
        Prolog p = null; //TODO lookup the selected prolog from first arg
        String query = null;
        String[] variableNames = null;                
        
        // execute
        nars.prolog.Term[] resolvedVariableValues = prologParseAndExecuteAndDereferenceInput(p, query, variableNames);
       
       
       
        // TODO< convert the result from the prolog to strings >
        memory.output(Prolog.class, query + " | TODO");
        //memory.output(Prolog.class, query + " | " + result);
       
        // set result values
        Term[] resultTerms = getResultVariablesFromPrologVariables(resolvedVariableValues, args);
       
        // create evaluation result
        //  compose operation before resultTerms
        int i;
       
        Term[] resultProductTerms = new Term[1 + resultTerms.length];
        resultProductTerms[0] = operation;
        for( i = 0; i < resultTerms.length; i++ ) {
            resultProductTerms[i + 1] = resultTerms[i];
        }
       
        //  create the nars result and return it
        Inheritance resultInheritance = Inheritance.make(
                Product.make(resultProductTerms, memory),
                new Term("prolog_evaluation"), memory);
       
        memory.output(Task.class, resultInheritance);
       
        ArrayList<Task> results = new ArrayList<>(1);
        results.add(memory.newTask(resultInheritance, Symbols.JUDGMENT_MARK, 1f, 0.99f, Parameters.DEFAULT_JUDGMENT_PRIORITY, Parameters.DEFAULT_JUDGMENT_DURABILITY));
               
        return results;
    }
    
    
    static private Term[] getResultVariablesFromPrologVariables(nars.prolog.Term[] prologVariables, Term[] args) {
        int numberOfVariables = (args.length - 2) / 2;
        int variableI;
       
        Term[] resultTerms = new Term[numberOfVariables];
       
        for( variableI = 0; variableI < numberOfVariables; variableI++ ) {
            if( prologVariables[variableI] instanceof Int ) {
                Int prologIntegerTerm = (Int)prologVariables[variableI];
               
                resultTerms[variableI] = new Term(String.valueOf(prologIntegerTerm.intValue()));
               
                continue;
            }
            else if( prologVariables[variableI] instanceof nars.prolog.Float ) {
                nars.prolog.Float prologFloatTerm = (nars.prolog.Float)prologVariables[variableI];
               
                resultTerms[variableI] = new Term(String.valueOf(prologFloatTerm.floatValue()));
               
                continue;
            }
            else if( prologVariables[variableI] instanceof Struct ) {
                Struct compoundTerm = (Struct)prologVariables[variableI];
               
                ArrayList<nars.prolog.Term> compundConvertedToArray = convertChainedCompoundTermToList(compoundTerm);
               
                try {
                    String variableAsString = tryToConvertPrologListToString(compundConvertedToArray);
                   
                    resultTerms[variableI] = new Term("\"" + variableAsString + "\"");
               
                    continue; // for debugging
                }
                catch( PrologTheoryOperator.ConversionFailedException conversionFailedException ) {
                    // the alternative is a product of numbers
                    // ASK< this may be not 100% correct, because prolog lists can be in lists etc >
                   
                    // TODO
                   
                    throw new RuntimeException("TODO");
                }
               
                // unreachable
            }
           
            throw new RuntimeException("Unhandled type of result variable");
        }
       
        return resultTerms;
    }
   
   
    private nars.prolog.Term[] prologParseAndExecuteAndDereferenceInput(Prolog p, String input, String[] dereferencingVariableNames) {
        nars.prolog.Term term = prologParseInput(input);
        return executePrologGoalAndDereferenceVariable(p, term, dereferencingVariableNames);
    }
   
    private nars.prolog.Term prologParseInput(String input) {
        try {
            Agent a = new Agent(input);
            Prolog p = new Prolog();
            SolveInfo s = p.addTheory(new Theory(input));
            return s.getSolution();
        } catch (InvalidTheoryException ex) {
            Logger.getLogger(PrologTheoryOperator.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSolutionException ex) {
            Logger.getLogger(PrologTheoryOperator.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }
   
    // TODO< return status/ error/sucess >
    private nars.prolog.Term[] executePrologGoalAndDereferenceVariable(Prolog p, nars.prolog.Term goalTerm, String[] variableName) {
        
        SolveInfo solution = p.solve(goalTerm);
        
        if (solution == null ) {
                return null; // TODO error
            }
       

        nars.prolog.Term[] resultArray;

        resultArray = new nars.prolog.Term[variableName.length];

        int variableI;

        for( variableI = 0; variableI < variableName.length; variableI++ ) {
            // get variable and dereference
            //  get the variable which has the name
            Var variableTerm = getVariableByNameRecursive(goalTerm, variableName[variableI]);

            if( variableTerm == null )
            {
                return null; // error
            }

            variableTerm.resolveTerm();
            nars.prolog.Term dereferencedTerm = variableTerm.getTerm();

            resultArray[variableI] = dereferencedTerm;
        }

        return resultArray;
    }
   
    // tries to get a variable from a term by name
    // returns null if it wasn't found
    static private nars.prolog.Var getVariableByNameRecursive(nars.prolog.Term term, String name) {
        if( term instanceof Struct ) {
            Struct s = (Struct)term;
            for (int i = 0; i < s.getArity(); i++) {
                nars.prolog.Term iterationTerm = s.getArg(i);
                nars.prolog.Var result = getVariableByNameRecursive(iterationTerm, name);
               
                if( result != null ) {
                    return result;
                }
            }
           
            return null;
        }
        else if( term instanceof nars.prolog.Var ) {
            if( ((nars.prolog.Var)term).name().equals(name) ) {
                return (nars.prolog.Var)term;
            }
           
            return null;
        }
        else if( term instanceof Int ) {
            return null;
        }
       
        throw new RuntimeException("Internal Error: Unknown prolog term!");
    }
   
    // converts a chained compound term (which contains oher compound terms) to a list
    static private ArrayList<nars.prolog.Term> convertChainedCompoundTermToList(Struct compoundTerm) {
        ArrayList<nars.prolog.Term> result = new ArrayList<>();
       
        Struct currentCompundTerm = compoundTerm;
       
        for(;;) {
            if( currentCompundTerm.getArity() == 0 ) {
                // end is reached
                break;
            }
            else if( currentCompundTerm.getArity() != 2 ) {
                throw new RuntimeException("Compound must have two or zero arguments!");
            }
           
            result.add(currentCompundTerm.getArg(0));
           
            nars.prolog.Term arg2 = currentCompundTerm.getArg(1);
            
            if ( arg2.isAtom()) {
                Struct atomTerm = (Struct)arg2;
               
                /*if( !atomTerm.value.equals("[]") ) {
                    throw new RuntimeException("[] AtomTerm excepted!");
                }*/
               
                // this is the last element of the list, we are done
                break;
            }
           
            if( !(arg2 instanceof Struct) ) {
                throw new RuntimeException("Second argument of Compound term is expected to be a compound term!");
            }
           
            currentCompundTerm = (Struct)(arg2);
        }
       
        return result;
    }
   
    // tries to convert a list with integer terms to an string
    // checks also if the signs are correct
    // throws an ConversionFailedException if the conversion is not possible
    static private String tryToConvertPrologListToString(ArrayList<nars.prolog.Term> array) {
        String result = "";
       
        for( nars.prolog.Term iterationTerm : array ) {
            if( !(iterationTerm instanceof Int) ) {
                throw new PrologTheoryOperator.ConversionFailedException();
            }
           
            Int integerTerm = (Int)iterationTerm;
            int integer = integerTerm.intValue();
           
            if( integer > 127 || integer < 0 ) {
                throw new PrologTheoryOperator.ConversionFailedException();
            }
           
            result += Character.toString((char)integer);
        }
       
        return result;
    }
   
    
    
    
}
