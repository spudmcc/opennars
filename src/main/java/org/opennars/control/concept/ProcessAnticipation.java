/* 
 * The MIT License
 *
 * Copyright 2018 The OpenNARS authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.opennars.control.concept;

import java.util.*;

import org.opennars.control.DerivationContext;
import org.opennars.entity.BudgetValue;
import org.opennars.entity.Concept;
import org.opennars.entity.Sentence;
import org.opennars.entity.Stamp;
import org.opennars.entity.Task;
import org.opennars.entity.TaskLink;
import org.opennars.entity.TermLink;
import org.opennars.entity.TruthValue;
import org.opennars.inference.RuleTables;
import org.opennars.inference.TemporalRules;
import org.opennars.interfaces.Timable;
import org.opennars.io.Symbols;
import org.opennars.io.events.OutputHandler;
import org.opennars.language.*;
import org.opennars.main.Nar;
import org.opennars.main.Parameters;
import org.opennars.operator.Operator;
import org.opennars.operator.mental.Anticipate;

import static org.opennars.inference.UtilityFunctions.c2w;
import static org.opennars.inference.UtilityFunctions.w2c;

/**
 *
 * @author Patrick Hammer
 */
public class ProcessAnticipation {

    private static void keepCovariantTableUnderAikr(Concept c, Parameters reasonerParameters) {
        // keep under AIKR by limiting memory
        // heuristic: use latest time of modification as heuristic

        if (c.covariantPredictions.size() <= reasonerParameters.COVARIANCE_TABLE_ENTRIES) {
            return;
        }

        int idxWithLowest = 0;
        long latestTimestamp = c.covariantPredictions.get(0).timestampOfLastUpdate;
        for(int idx=0;idx<c.covariantPredictions.size();idx++) {
            Concept.Predicted iPredicted = c.covariantPredictions.get(idx);
            if (iPredicted.timestampOfLastUpdate < latestTimestamp) {
                latestTimestamp = iPredicted.timestampOfLastUpdate;
                idxWithLowest = idx;
            }
        }
        c.covariantPredictions.remove(idxWithLowest);
    }

    public static void addCovariantAnticipationEntry(Implication impl, final DerivationContext nal) {
        Term conditionalWithVars = impl.getSubject();

        Term conditionalWithQuantizedIntervalsWithVars = extractSeqQuantizedWithInfiniteQuant((Conjunction)conditionalWithVars);
        Term conditionedWithVars = impl.getPredicate();

        Set<Term> targets = new LinkedHashSet<>();
        {
            //add to all components, unless it doesn't have vars
            Map<Term, Integer> ret = CompoundTerm.replaceIntervals(impl).countTermRecursively(null);
            for(Term r : ret.keySet()) {
                targets.add(r);
            }
        }


        //Debug.debug(false, "addCovariantAnticipationEntry", "");
        //Debug.debug(false, "addCovariantAnticipationEntry","   cond  = " + conditionalWithQuantizedIntervalsWithVars);
        //Debug.debug(false, "addCovariantAnticipationEntry","   eff   = " + conditionedWithVars);

        for(Term iTarget : targets) { // iterate over sub-terms
            final Concept targetConcept = nal.memory.concept(iTarget);
            if (targetConcept == null) { // target concept does not exist
                continue;
            }

            //Debug.debug(false, "addCovariantAnticipationEntry", "  c=" + iTarget);


            synchronized (targetConcept) {
                Concept.Predicted matchingCovarianceEntry = null;
                { // search for matching conditional in (co)variances

                    // TODO< speed up when finalized >
                    for(int idx=0;idx<targetConcept.covariantPredictions.size();idx++) {
                        Concept.Predicted iPredicted = targetConcept.covariantPredictions.get(idx);
                        if (iPredicted.conditionalTerm.equals(conditionalWithQuantizedIntervalsWithVars) && iPredicted.conditionedTerm.equals(conditionedWithVars)) {
                            matchingCovarianceEntry = iPredicted;
                            break;
                        }
                    }
                }

                boolean found = matchingCovarianceEntry != null;
                if (found) {
                    Interval lastInterval = retLastInterval((Conjunction)conditionalWithVars);
                    if (lastInterval == null) {
                        continue; // doesn't have a interval at the last place - return
                    }
                    float timeDelta = lastInterval.time;

                    matchingCovarianceEntry.dist.next(timeDelta);
                    matchingCovarianceEntry.timestampOfLastUpdate = nal.time.time();
                }
                else {
                    keepCovariantTableUnderAikr(targetConcept, nal.narParameters);

                    // add entry

                    Interval lastInterval = retLastInterval((Conjunction)conditionalWithVars);
                    if (lastInterval == null) {
                        continue; // doesn't have a interval at the last place - return
                    }
                    float timeDelta = lastInterval.time;

                    Concept.Predicted newPredicted = new Concept.Predicted(conditionalWithQuantizedIntervalsWithVars, conditionedWithVars, nal.time.time(), timeDelta);
                    targetConcept.covariantPredictions.add(newPredicted);
                }
            }
        }


    }

    public static AnticipationTimes anticipationEstimateMinAndMaxTimes(final DerivationContext nal, final Sentence mainSentence, Map<Term,Term> substitution) {
        return anticipationEstimateMinAndMaxTimes(nal, (Implication)mainSentence.term, substitution, mainSentence.getOccurenceTime());
    }

    public static AnticipationTimes anticipationEstimateMinAndMaxTimes(final DerivationContext nal, final Implication impl, Map<Term,Term> substitution, long occurenceTime) {
        Concept implConcept = nal.memory.conceptualize(new BudgetValue(1.0f, 0.98f, 1.0f, nal.narParameters), CompoundTerm.replaceIntervals(impl));

        Term conditionalWithVars = impl.getSubject();
        Term conditionalWithQuantizedIntervalsWithVars = extractSeqQuantizedWithInfiniteQuant((Conjunction)conditionalWithVars);
        Term conditionedWithVars = impl.getPredicate();


        Concept.Predicted matchingCovarianceEntry = null;
        { // search for matching conditional in (co)variances

            // TODO< speed up when finalized >
            for(int idx=0;idx<implConcept.covariantPredictions.size();idx++) {
                Concept.Predicted iPredicted = implConcept.covariantPredictions.get(idx);
                if (iPredicted.conditionalTerm.equals(conditionalWithQuantizedIntervalsWithVars) && iPredicted.conditionedTerm.equals(conditionedWithVars)) {
                    matchingCovarianceEntry = iPredicted;
                    break;
                }
            }
        }

        boolean found = matchingCovarianceEntry != null;
        if( !found) {
            return null; // we can't estimate a covariance if we don't know anything about it
        }
        else if (matchingCovarianceEntry.dist.n <= 1) {
            return null; // to few samples to make a meaningful estimation!
        }


        float timeOffset = 0, timeWindowHalf = 0;
        { // sample from distribution
            float mean = (float) matchingCovarianceEntry.dist.mean;
            float variance = (float) matchingCovarianceEntry.dist.calcVariance();

            float scaledVariance = variance * nal.narParameters.COVARIANCE_WINDOW;
            timeWindowHalf = scaledVariance * 0.5f;
            timeOffset = sumOfIntervalsExceptLastOne((Conjunction) conditionalWithVars) + mean;
        }

        // debug
        {
            if (false && matchingCovarianceEntry.dist.calcVariance() > 0.00001) {

                boolean isPredictiveBySeq =
                    impl instanceof Implication &&
                        impl.getTemporalOrder() == TemporalRules.ORDER_FORWARD &&
                        ((Implication)impl).getSubject() instanceof CompoundTerm &&
                        ((CompoundTerm)((Implication)impl).getSubject()).term.length > 2;
                if (isPredictiveBySeq) {
                    System.out.println("ProcessAnticipation.anticipationEstimateMinAndMaxTimes(): successfull call anticipate for term=" + impl + " (" + (timeOffset - timeWindowHalf) + ";" + (timeOffset + timeWindowHalf) + ")");

                    int debugHere = 5;
                }
            }
        }


        // assert timeOffset != 0 and timeWindowHalf != 0

        AnticipationTimes result = new AnticipationTimes();
        result.occurrenceTime = occurenceTime;
        result.timeWindow = timeWindowHalf * 2.0f;
        result.timeOffset = timeOffset;

        //Debug.debug(false, "anticipationEstimateMinAndMaxTimes","");
        //Debug.debug(false, "anticipationEstimateMinAndMaxTimes","   term = " + impl);
        //Debug.debug(false, "anticipationEstimateMinAndMaxTimes","   n    = " + matchingCovarianceEntry.dist.n);
        //Debug.debug(false, "anticipationEstimateMinAndMaxTimes","   ===> timeWindow=" + result.timeWindow);

        return result;
    }

    public static void anticipateEstimate(final DerivationContext nal, final Sentence mainSentence, final BudgetValue budget,
                                          final float priority, Map<Term,Term> substitution, AnticipationTimes anticipationTimes) {


        //Debug.debug(false, "ProcessAnticipation:estimate()", "ENTRY");

        if (anticipationTimes == null) { // if we need to estimate the anticipation time from the sentence
            if (mainSentence.isEternal()) {
                return; // is actually not allow and a BUG when we land here
                // for now it's fine to just return
            }

            anticipationTimes = anticipationEstimateMinAndMaxTimes(nal, (Implication)mainSentence.term, substitution, mainSentence.getOccurenceTime());
            if (anticipationTimes == null) {
                return;
            }

            //Debug.debug(false, "ProcessAnticipation:estimate()", "ProcessAnticipation:estimate() successfully estimated min/max");
        }





        long occurenceTime = anticipationTimes.occurrenceTime;
        float timeOffset = anticipationTimes.timeOffset;
        float timeWindowHalf = anticipationTimes.timeWindow * 0.5f;

        // assert timeOffset != 0 and timeWindowHalf != 0


        long mintime = (long) Math.max(occurenceTime, (occurenceTime + timeOffset - timeWindowHalf - 1));
        long maxtime = (long) (occurenceTime + timeOffset + timeWindowHalf + 1);

        if (maxtime < 0) {
            int debug6 = 5; // must never happen!
        }

        //System.out.println("call anticipate for term=" + mainSentence.term + " (" + (timeOffset-timeWindowHalf) + ";" + (timeOffset+timeWindowHalf) + ")");

        anticipate(nal, mainSentence.term, mintime, maxtime, priority, substitution);
    }

    public static void anticipate(final DerivationContext nal, final Term term,
            final long mintime, final long maxtime, final float priority, Map<Term,Term> substitution) {
        //derivation was successful and it was a judgment event
        final Stamp stamp = new Stamp(nal.time, nal.memory);
        stamp.setOccurrenceTime(Stamp.ETERNAL);
        float eternalized_induction_confidence = nal.memory.narParameters.ANTICIPATION_CONFIDENCE;
        final Sentence s = new Sentence(
            term,
            '.',
            new TruthValue(0.0f, eternalized_induction_confidence, nal.narParameters),
            stamp);
        final Task t = new Task(s, new BudgetValue(0.99f,0.1f,0.1f, nal.narParameters), Task.EnumType.DERIVED); //Budget for one-time processing
        Term specificAnticipationTerm = ((CompoundTerm)((Statement) term).getPredicate()).applySubstitute(substitution);
        final Concept c = nal.memory.concept(specificAnticipationTerm); //put into consequence concept
        if(c != null /*&& mintime > nal.memory.time()*/ && c.observable && (term instanceof Implication || term instanceof Equivalence) &&
                term.getTemporalOrder() == TemporalRules.ORDER_FORWARD) {
            Concept.AnticipationEntry toDelete = null;
            Concept.AnticipationEntry toInsert = new Concept.AnticipationEntry(priority, t, mintime, maxtime);
            boolean fullCapacity = c.anticipations.size() >= nal.narParameters.ANTICIPATIONS_PER_CONCEPT_MAX;
            //choose an element to replace with the new, in case that we are already at full capacity
            if(fullCapacity) {
                for(Concept.AnticipationEntry entry : c.anticipations) {
                    if(priority > entry.negConfirmationPriority /*|| t.getPriority() > c.negConfirmation.getPriority() */) {
                        //prefer to replace one that is more far in the future, takes longer to be disappointed about
                        if(toDelete == null || entry.negConfirm_abort_maxtime > toDelete.negConfirm_abort_maxtime) {
                            toDelete = entry;
                        }
                    }
                }
            }
            //we were at full capacity but there was no item that can be replaced with the new one
            if(fullCapacity && toDelete == null) {
                return;
            }
            if(toDelete != null) {
                c.anticipations.remove(toDelete);
            }
            c.anticipations.add(toInsert);
            final Statement impOrEqu = (Statement) toInsert.negConfirmation.sentence.term;
            final Concept ctarget = nal.memory.concept(impOrEqu.getPredicate());
            if(ctarget != null) {
                Operator anticipate_op = ((Anticipate)c.memory.getOperator("^anticipate"));
                if(anticipate_op != null && anticipate_op instanceof Anticipate) {
                    ((Anticipate)anticipate_op).anticipationFeedback(impOrEqu.getPredicate(), null, c.memory, nal.time);
                }
            }
            nal.memory.emit(OutputHandler.ANTICIPATE.class, specificAnticipationTerm); //disappoint/confirm printed anyway
        }
   
    }

    /**
     * Process outdated anticipations within the concept,
     * these which are outdated generate negative feedback
     * 
     * @param narParameters The reasoner parameters
     * @param concept The concept which potentially outdated anticipations should be processed
     * @param nar the reasoner
     */
    public static void maintainDisappointedAnticipations(final Parameters narParameters, final Concept concept, final Nar nar) {
        //here we can check the expiration of the feedback:
        List<Concept.AnticipationEntry> confirmed = new ArrayList<>();
        List<Concept.AnticipationEntry> disappointed = new ArrayList<>();
        for(Concept.AnticipationEntry entry : concept.anticipations) {
            if(entry.negConfirmation == null || nar.time() <= entry.negConfirm_abort_maxtime) {
                continue;
            }
            //at first search beliefs for input tasks:
            boolean gotConfirmed = false;
            if(narParameters.RETROSPECTIVE_ANTICIPATIONS) {
                for(final TaskLink tl : concept.taskLinks) { //search for input in tasklinks (beliefs alone can not take temporality into account as the eternals will win)
                    final Task t = tl.targetTask;
                    if(t!= null && t.sentence.isJudgment() && t.isInput() && !t.sentence.isEternal() && t.sentence.truth.getExpectation() > concept.memory.narParameters.DEFAULT_CONFIRMATION_EXPECTATION &&
                            CompoundTerm.replaceIntervals(t.sentence.term).equals(CompoundTerm.replaceIntervals(concept.getTerm()))) {
                        if(t.sentence.getOccurenceTime() >= entry.negConfirm_abort_mintime && t.sentence.getOccurenceTime() <= entry.negConfirm_abort_maxtime) {
                            confirmed.add(entry);
                            gotConfirmed = true;
                            break;
                        }
                    }
                }
            }
            if(!gotConfirmed) {
                disappointed.add(entry);
            }
        }
        //confirmed by input, nothing to do
        if(confirmed.size() > 0) {
            concept.memory.emit(OutputHandler.CONFIRM.class,concept.getTerm());
        }
        concept.anticipations.removeAll(confirmed);
        //not confirmed and time is out, generate disappointment
        if(disappointed.size() > 0) {
            concept.memory.emit(OutputHandler.DISAPPOINT.class,concept.getTerm());
        }
        for(Concept.AnticipationEntry entry : disappointed) {
            final Term term = entry.negConfirmation.getTerm();
            final Term termWithRplacedIntervals = CompoundTerm.replaceIntervals(term);

            { // revise with negative evidence
                TruthValue truthOfBeliefWithTerm = null;
                {
                    final Concept targetConcept = nar.memory.concept(termWithRplacedIntervals);
                    if (targetConcept == null) { // target concept does not exist
                        continue;
                    }

                    synchronized (targetConcept) {
                        for( final Task iBeliefTask : targetConcept.beliefs ) {
                            Term iBeliefTerm = iBeliefTask.getTerm();

                            boolean found = iBeliefTerm.equals(term);
                            if (found) {
                                truthOfBeliefWithTerm = iBeliefTask.sentence.truth;
                                break;
                            }
                        }
                    }
                }



                if(truthOfBeliefWithTerm != null) {
                    // compute amount of negative evidence based on current evidence
                    // we just take the counter and don't add one because we want to compute a w "unit" which will be revised
                    long countWithNegativeEvidence = ((Implication)term).counter;
                    double negativeEvidenceRatio = 1.0 / (double) countWithNegativeEvidence;

                    // compute confidence by negative evidence
                    double w = c2w(truthOfBeliefWithTerm.getConfidence(), narParameters);
                    w *= negativeEvidenceRatio;
                    float c = w2c((float) w, narParameters);

                    final TruthValue truth = new TruthValue(0.0f, c, narParameters); // frequency of negative confirmation is 0.0

                    final Sentence sentenceForNewTask = new Sentence(
                        term,
                        Symbols.JUDGMENT_MARK,
                        truth,
                        new Stamp(nar, nar.memory, Tense.Eternal));
                    final BudgetValue budget = new BudgetValue(0.99f, 0.1f, 0.1f, nar.narParameters);
                    final Task t = new Task(sentenceForNewTask, budget, Task.EnumType.DERIVED);

                    concept.memory.inputTask(nar, t, false);
                }
            }

            concept.anticipations.remove(entry);
        }
    }
    
    /**
     * Whether a processed judgement task satisfies the anticipations within concept
     * 
     * @param task The judgement task be checked
     * @param concept The concept that is processed
     * @param nal The derivation context
     */
    public static void confirmAnticipation(Task task, Concept concept, final DerivationContext nal) {
        final boolean satisfiesAnticipation = task.isInput() && !task.sentence.isEternal();
        final boolean isExpectationAboveThreshold = task.sentence.truth.getExpectation() > nal.narParameters.DEFAULT_CONFIRMATION_EXPECTATION;
        List<Concept.AnticipationEntry> confirmed = new ArrayList<>();
        for(Concept.AnticipationEntry entry : concept.anticipations) {
            if(satisfiesAnticipation && isExpectationAboveThreshold && task.sentence.getOccurenceTime() > entry.negConfirm_abort_mintime) {
                confirmed.add(entry);
            }
        }
        if(confirmed.size() > 0) {
            nal.memory.emit(OutputHandler.CONFIRM.class, concept.getTerm());
        }
        concept.anticipations.removeAll(confirmed);
    }
    
    /**
     * Fire predictictive inference based on beliefs that are known to the concept's neighbours
     * 
     * @param judgementTask judgement task
     * @param concept concept that is processed
     * @param nal derivation context
     * @param time used to retrieve current time
     * @param tasklink coresponding tasklink
     */
    public static void firePredictions(final Task judgementTask, final Concept concept, final DerivationContext nal, Timable time, TaskLink tasklink) {
        if(!judgementTask.sentence.isEternal() && judgementTask.isInput() && judgementTask.sentence.isJudgment()) {
            for(TermLink tl : concept.termLinks) {
                Term term = tl.getTarget();
                Concept tc = nal.memory.concept(term);
                if(tc != null && !tc.beliefs.isEmpty() && term instanceof Implication) {
                    Implication imp = (Implication) term;
                    if(imp.getTemporalOrder() == TemporalRules.ORDER_FORWARD) {
                        Term precon = imp.getSubject();
                        Term component = precon;
                        if(precon instanceof Conjunction) {
                            Conjunction conj = (Conjunction) imp.getSubject();
                            if(conj.getTemporalOrder() == TemporalRules.ORDER_FORWARD && conj.term.length == 2 && conj.term[1] instanceof Interval) {
                                component = conj.term[0]; //(&/,a,+i), so use a
                            }
                        }
                        if(CompoundTerm.replaceIntervals(concept.getTerm()).equals(CompoundTerm.replaceIntervals(component))) {
                            //trigger inference of the task with the belief
                            DerivationContext cont = new DerivationContext(nal.memory, nal.narParameters, time);
                            cont.setCurrentTask(judgementTask); //a
                            cont.setCurrentBeliefLink(tl); // a =/> b
                            cont.setCurrentTaskLink(tasklink); // a
                            cont.setCurrentConcept(concept); //a
                            cont.setCurrentTerm(concept.getTerm()); //a
                            RuleTables.reason(tasklink, tl, cont); //generate b
                        }
                    }
                }
            }
        }
    }

    private static Conjunction quantizeSeq(Conjunction term, int quantization) {
        Term[] arr = new Term[term.term.length];
        for(int i=0;i<term.term.length;i++) {
            arr[i] = term.term[i];

            if (!(term.term[i] instanceof Interval)) {
                continue;
            }

            Interval interval = (Interval)term.term[i];
            long intervalTime = interval.time;
            // quantize
            intervalTime = (intervalTime / quantization) * quantization;

            arr[i] = new Interval(intervalTime);
        }

        return (Conjunction)Conjunction.make(arr, term.temporalOrder, term.isSpatial);
    }

    private static Term extractSeqQuantizedWithInfiniteQuant(Conjunction term) {
        return extractSeqQuantized(term, Integer.MAX_VALUE);
    }

    private static Term extractSeqQuantized(Conjunction term, final int quantization) {
        Conjunction quantized = quantizeSeq(term, quantization);

        int cutoff = 0; // we want to cutt of the last interval
        if (quantized.term[quantized.term.length-1] instanceof Interval) {
            cutoff = 1;
        }

        Term[] arr = new Term[quantized.term.length-cutoff];
        for(int i=0;i<(quantized.term.length-cutoff);i++) {
            arr[i] = quantized.term[i];
        }

        if(arr.length == 1) {
            return arr[0]; // return term if it is the only content of the conjunction
        }
        return Conjunction.make(arr, term.temporalOrder, term.isSpatial);
    }

    private static Interval retLastInterval(Conjunction term) {
        if (term.term[term.term.length-1] instanceof Interval) {
            return (Interval)term.term[term.term.length-1];
        }
        return null;
    }

    private static long sumOfIntervalsExceptLastOne(Conjunction term) {
        long sum = 0;
        for(int i=0;i<term.term.length-1;i++) {
            if (!(term.term[i] instanceof Interval)) {
                continue;
            }

            Interval interval = (Interval) term.term[i];
            long intervalTime = interval.time;
            sum += intervalTime;
        }
        return sum;
    }

    public static class AnticipationTimes {
        public float timeOffset;
        public float timeWindow;
        public long occurrenceTime; // occurence time of the sentence - is the time of the first event in the sentence
    }
}
