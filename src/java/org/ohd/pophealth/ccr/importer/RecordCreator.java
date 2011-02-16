/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.ohd.pophealth.ccr.importer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.astm.ccr.ActorReferenceType;
import org.astm.ccr.ActorType;
import org.astm.ccr.ActorType.Person.Name;
import org.astm.ccr.Agent;
import org.astm.ccr.AlertType;
import org.astm.ccr.CCRCodedDataObjectType;
import org.astm.ccr.CodeType;
import org.astm.ccr.CodedDescriptionType;
import org.astm.ccr.ContinuityOfCareRecord;
import org.astm.ccr.DateTimeType;
import org.astm.ccr.EncounterType;
import org.astm.ccr.GoalType;
import org.astm.ccr.PlanOfCareType;
import org.astm.ccr.PlanType;
import org.astm.ccr.ProblemType;
import org.astm.ccr.ProcedureType;
import org.astm.ccr.ResultType;
import org.astm.ccr.SocialHistoryType;
import org.astm.ccr.StructuredProductType;
import org.astm.ccr.StructuredProductType.Product;
import org.astm.ccr.TestType;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.ohd.pophealth.json.clinicalmodel.Actor;
import org.ohd.pophealth.json.clinicalmodel.Allergy;
import org.ohd.pophealth.json.clinicalmodel.BaseObject;
import org.ohd.pophealth.json.clinicalmodel.Medication;
import org.ohd.pophealth.json.clinicalmodel.Test;
import org.ohd.pophealth.json.measuremodel.CodedValue;
import org.ohd.pophealth.json.clinicalmodel.Condition;
import org.ohd.pophealth.json.clinicalmodel.Encounter;
import org.ohd.pophealth.json.clinicalmodel.Goal;
import org.ohd.pophealth.json.clinicalmodel.Order;
import org.ohd.pophealth.json.clinicalmodel.Patient;
import org.ohd.pophealth.json.clinicalmodel.Procedure;
import org.ohd.pophealth.json.clinicalmodel.Record;
import org.ohd.pophealth.json.clinicalmodel.Result;

/**
 * This class handles the extraction of clinical data from the ASTM CCR into a
 * set of standard categories for matching against measures
 * @author ohdohd
 */
public class RecordCreator {

    private final static Logger LOG = Logger.getLogger(RecordCreator.class.getName());
    // Current CCR being worked on
    private ContinuityOfCareRecord ccr;
    // Controlled Vocabulary for working with CCR
    private Vocabulary v;
    // Required TermSets
    private static final String[] requiredTermSets = {"onset", "occurred",
        "resolved", "ended", "collected", "ordered", "gender_male", "gender_female"};
    private DateTimeFormatter fmt = ISODateTimeFormat.dateTimeParser(); // Formatter to parse ISO8601 Date Strings

    /**
     * Construct a RecordCreator using a particular Vocabulary.  There is a base
     * vocabulary that can be used: org.ohd.pophealth.ccr.importer.ccrvocabulary.json
     *
     * @param vocab CCR Vocabulary to use during CCR data extraction
     * @throws InCompleteVocabularyException Thrown if not all of the required
     *      termsets are in the passed Vocabulary.  Can call <code>getRequiredTermSets()</code>
     *      to get a list of required termsets for this RecordCreator
     */
    public RecordCreator(Vocabulary vocab) throws InCompleteVocabularyException {
        //Check that required TermSet are in the vocabulary
        for (String s : requiredTermSets) {
            if (!vocab.isValidTermSet(s)) {
                throw new InCompleteVocabularyException("Did not find required termset: " + s);
            }
        }
        this.v = vocab;
    }

    /**
     * Returns a list of names of required term sets
     *
     * @return Array of termset ids
     */
    public static String[] getRequiredTermSets() {
        return requiredTermSets;
    }

    /**
     * Creates a <code>Record</code> from the passed CCR
     *
     * @param ccr  The CCR as an object generated by JAXB
     * @return  the extracted record
     */
    public Record createRecord(ContinuityOfCareRecord ccr) {
        this.ccr = ccr;
        // Create a new Record and set all the attributes
        Record r = new Record();
        r.setPatient(createPatient());
        r.setActors(createActors());
        r.setConditions(createConditions());
        r.setEncounters(createEncounters());
        r.setProcedures(createProcedures());
        r.setResults(createResults());
        r.setMedications(createMedications());
        r.setAllergies(createAllergies());
        r.setOrders(createOrders());

        if (LOG.isLoggable(Level.FINEST)) {
            try {
                LOG.finest(r.toJson(true));
            } catch (JsonMappingException ex) {
                Logger.getLogger(RecordCreator.class.getName()).log(Level.SEVERE, null, ex);
            } catch (JsonGenerationException ex) {
                Logger.getLogger(RecordCreator.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Logger.getLogger(RecordCreator.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return r;
    }

    /*
     * This method walks through the list of CCR Actors and finds the Actor
     * with an <ActorObjectID> equal to the passed actorid.
     */
    private ActorType getActorById(String actorid) {
        for (ActorType a : ccr.getActors().getActor()) {
            if (actorid.equals(a.getActorObjectID())) {
                return a;
            }
        }
        return null;
    }

    /*
     * Extracts the data for the <code>Patient</code> object for the <code>
     * Record</code>
     */
    private Patient createPatient() {
        ActorType pt = getActorById(ccr.getPatient().get(0).getActorID());
        Patient p = new Patient();

        // Set Date Of Birth - Assumes DOB required by CCR Validation
        if (pt.getPerson() != null && pt.getPerson().getDateOfBirth() != null){
            DateTimeType dt = pt.getPerson().getDateOfBirth();
        String dob = dt.getExactDateTime();
        p.setBirthdate(convertISO8601toSecfromEpoch(dob));
        }else{
            p.setBirthdate(BaseObject.minDate);
        }

        // Set Name
        if (pt.getPerson() != null && pt.getPerson().getName() != null){
            Name n = pt.getPerson().getName();
            // Try Current Name first
            if (n.getCurrentName() != null){
                if (!n.getCurrentName().getFamily().isEmpty()){
                    // Just grab the first family name
                    // TODO handle multiple last names
                    p.setLast(n.getCurrentName().getFamily().get(0));
                }
                if (!n.getCurrentName().getGiven().isEmpty()){
                    p.setFirst(n.getCurrentName().getGiven().get(0));
                }
            // Second try birth name
            }else if (n.getBirthName() != null){
                if (!n.getBirthName().getFamily().isEmpty()){
                    // Just grab the first family name
                    // TODO handle multiple last names
                    p.setLast(n.getBirthName().getFamily().get(0));
                }
                if (!n.getBirthName().getGiven().isEmpty()){
                    p.setFirst(n.getBirthName().getGiven().get(0));
                }
            }
            // TODO Add code to finally check for Additional Names
        }

        // Set Gender
        if (pt.getPerson() != null  && pt.getPerson().getGender() != null) {
            if (isConceptMatch(v.getTermSet("gender_male"), pt.getPerson().getGender())) {
                p.setGender("M");
            } else if (isConceptMatch(v.getTermSet("gender_female"), pt.getPerson().getGender())) {
                p.setGender("F");
            }
        }
        // TODO Get Race, Ethnicity in the future


        return p;
    }

    /**
     * Converts a full or partial ISO8601 date string into milliseconds for epoch
     * @param iso a full or partial datetime in ISO8601 format
     * @return returns milliseconds from Epoch, if there is a problem returns -999999999999999
     */
    public long convertISO8601toMSfromEpoch(String iso) {
        if (iso == null || "".equals(iso)) {
            return -999999999999999L;
        }
        DateTime dt = fmt.parseDateTime(iso);
        return dt.getMillis();
    }

    /**
     * Converts a full or partial ISO8601 date string into seconds for epoch
     * @param iso a full or partial datetime in ISO8601 format
     * @return returns seconds from Epoch, if there is a problem returns -999999999999
     */
    public long convertISO8601toSecfromEpoch(String iso) {
        return convertISO8601toMSfromEpoch(iso) / 1000;
    }

    /*
     * Walks through the <Actor> nodes in /ContinuityOfCareRecord/Actors and
     * creates clinical model Actors
     */
    private ArrayList<Actor> createActors() {
        ArrayList<Actor> al = new ArrayList<Actor>();
        // Double check there are Actors in the CCR although it should not be the case there are none
        if (ccr.getActors() != null) {
            for (ActorType at : ccr.getActors().getActor()) {
                Actor a = new Actor(at.getActorObjectID());
                al.add(a);
            }
        }
        return al;
    }

    /*
     * Walks through the <Problem> nodes in /ContinuityOfCareRecord/Body/Problems
     * and creates clinical model Conditions.  Also walks through <SocialHistoryElement>
     * nodes in /ContinuityOfCareRecord/Body/SocialHistory and creates clinical model conditions
     */
    private ArrayList<Condition> createConditions() {
        ArrayList<Condition> cl = new ArrayList<Condition>();
        // Walk through CCR Problems
        if (ccr.getBody().getProblems() != null) {
            for (ProblemType pt : ccr.getBody().getProblems().getProblem()) {
                Condition c = new Condition(pt.getCCRDataObjectID());
                // Set Description
                if (pt.getDescription() != null) {
                    c.setDescription(convertToCodedValue(pt.getDescription()));
                }
                // Set status of the condition (i.e. is it active, chronic, resolved, etc.)
                if (pt.getStatus() != null) {
                    c.setStatus(convertToCodedValue(pt.getStatus()));
                }
                // Set the onset date of the Condition
                String dateOnset = null;
                try {
                    dateOnset = findDate(v.getTermSet("onset"), pt.getDateTime());
                } catch (NoValidDateFound ex) {
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, "No onset date found for problem [{0}]", pt.getCCRDataObjectID());
                }
                if (dateOnset != null) {
                    c.setOnset(convertISO8601toSecfromEpoch(dateOnset));
                }
                // Set the resolution date of the Condition
                String dateResolve = null;
                try {
                    dateResolve = findDate(v.getTermSet("resolved"), pt.getDateTime());
                } catch (NoValidDateFound ex) {
                    // Not a problem many CCR Problems will not have a resolve date.
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.FINE, "No resolve date found for problem [{0}]", pt.getCCRDataObjectID());
                }
                if (dateResolve != null) {
                    c.setResolution(convertISO8601toSecfromEpoch(dateResolve));
                }
                //TODO set Type on Condition
                cl.add(c);
            }
        }
        // Walk through CCR SocialHistory
        if (ccr.getBody().getSocialHistory() != null) {
            for (SocialHistoryType sht : ccr.getBody().getSocialHistory().getSocialHistoryElement()) {
                Condition c = new Condition(sht.getCCRDataObjectID());
                // Set description
                if (sht.getDescription() != null) {
                    c.setDescription(convertToCodedValue(sht.getDescription()));
                }
                // Set status
                if (sht.getStatus() != null) {
                    c.setStatus(convertToCodedValue(sht.getStatus()));
                }
                // Set onset date
                String dateOnset = null;
                try {
                    dateOnset = findDate(v.getTermSet("onset"), sht.getDateTime());
                } catch (NoValidDateFound ex) {
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getLocalizedMessage());
                }
                if (dateOnset != null) {
                    c.setOnset(convertISO8601toSecfromEpoch(dateOnset));
                }
                // Set resolve date
                String dateResolve = null;
                try {
                    dateResolve = findDate(v.getTermSet("resolved"), sht.getDateTime());
                } catch (NoValidDateFound ex) {
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getLocalizedMessage());
                }
                if (dateResolve != null) {
                    c.setResolution(convertISO8601toSecfromEpoch(dateResolve));
                }
                //TODO set Type on Condition
                cl.add(c);
            }
        }
        return cl;
    }

    /*
     * Walks through the <Encounter> nodes in /ContinuityOfCareRecord/Body/Encounters
     * and creates clinical model Encounters
     */
    private ArrayList<Encounter> createEncounters() {
        ArrayList<Encounter> el = new ArrayList<Encounter>();
        if (ccr.getBody().getEncounters() != null) {
            for (EncounterType et : ccr.getBody().getEncounters().getEncounter()) {
                Encounter e = createEncounter(et);
                if (e != null) {
                    el.add(e);
                }
            }
        }
        return el;
    }

    private Encounter createEncounter(EncounterType et) {
        Encounter e = new Encounter(et.getCCRDataObjectID());
        // Set description
        if (et.getDescription() != null) {
            e.setDescription(convertToCodedValue(et.getDescription()));
        }
        // Set the list of practitioners
        if (et.getPractitioners() != null) {
            for (ActorReferenceType art : et.getPractitioners().getPractitioner()) {
                e.addProvider(art.getActorID());
            }
        }
        // Set the date the encounter occurred
        String dateOccurred = null;
        try {
            dateOccurred = findDate(v.getTermSet("occurred"), et.getDateTime());
        } catch (NoValidDateFound ex) {
            Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
        }
        if (dateOccurred != null) {
            e.setOccurred(convertISO8601toSecfromEpoch(dateOccurred));
        }
        // Set the date the encounter ended
        String dateEnded = null;
        try {
            dateEnded = findDate(v.getTermSet("ended"), et.getDateTime());
        } catch (NoValidDateFound ex) {
            Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
        }
        if (dateEnded != null) {
            e.setEnded(convertISO8601toSecfromEpoch(dateEnded));
        }
        return e;
    }

    /*
     * Walks through the <Result> nodes in both /ContinuityOfCareRecord/Body/Results
     * and /ContinuityOfCareRecord/Body/VitalSigns
     */
    private ArrayList<Result> createResults() {
        ArrayList<Result> rl = new ArrayList<Result>();
        // Create results from the CCR <Results> section
        if (ccr.getBody().getResults() != null) {
            for (ResultType rtr : ccr.getBody().getResults().getResult()) {
                rl.add(createResult(rtr));
            }
        }
        // Create results from the CCR <VitalSigns> section
        if (ccr.getBody().getVitalSigns() != null) {
            for (ResultType rtv : ccr.getBody().getVitalSigns().getResult()) {
                rl.add(createResult(rtv));
            }
        }
        return rl;
    }

    /*
     * Creates a clinical model result from a CCR ResultType
     */
    private Result createResult(ResultType rt) {
        Result r = new Result(rt.getCCRDataObjectID());
        // Set description
        if (rt.getDescription() != null) {
            r.setDescription(convertToCodedValue(rt.getDescription()));
        }
        // Set type
        if (rt.getType() != null) {
            r.setType(convertToCodedValue(rt.getType()));
        }
        // Set collection date
        String rCollectedDate = null;
        try {
            rCollectedDate = findDate(v.getTermSet("collected"), rt.getDateTime());
        } catch (NoValidDateFound ex) {
            Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
        }
        if (rCollectedDate != null) {
            r.setCollectionTime(convertISO8601toSecfromEpoch(rCollectedDate));
        }
        // Create and set the list of tests for this result
        r.setTests(createTests(rt, rCollectedDate));
        return r;
    }

    /*
     * Creates a list of clinical model Test objects from the <Test> children of
     * the CCR <Result>
     */
    private ArrayList<Test> createTests(ResultType rt, String resultCollectTime) {
        ArrayList<Test> tl = new ArrayList<Test>();
        if (rt != null) {
            for (TestType tt : rt.getTest()) {
                Test t = new Test(tt.getCCRDataObjectID());
                // Try to set the collection date of the test.  If the <Test> does
                // no have a collection date try to use the collection date of the
                // result.
                try {
                    // Find if there is a collection date for the test
                    String collectedDate = findDate(v.getTermSet("collected"), tt.getDateTime());
                    if (collectedDate != null) {
                        // Found a test collection date to use it
                        t.setCollectionTime(convertISO8601toSecfromEpoch(collectedDate));
                    } else if (resultCollectTime != null) {
                        // Did not find a test collection date so use result collection time
                        t.setCollectionTime(convertISO8601toSecfromEpoch(resultCollectTime));
                    }
                } catch (NoValidDateFound ex) {
                    // No problem, just use the result collected time
                    t.setCollectionTime(convertISO8601toSecfromEpoch(resultCollectTime));
                }
                // Set description
                if (tt.getDescription() != null) {
                    t.setDescription(convertToCodedValue(tt.getDescription()));
                }
                // Set the test value and units
                if (tt.getTestResult().getValue() != null) {
                    t.setValue(tt.getTestResult().getValue());
                    if (tt.getTestResult().getUnits() != null) {
                        if (tt.getTestResult().getUnits().getUnit() != null) {
                            t.setUnits(tt.getTestResult().getUnits().getUnit());
                        }
                    }
                }// TODO Else could set value based on <Description> of TestResult
                tl.add(t);
            }
        }
        return tl;
    }

    /*
     * Create clinical model Medications from CCR Medications in
     * /ContinuityOfCare/Body/Medications and /ContinuityOfCare/Body/Immunizations
     */
    private ArrayList<Medication> createMedications() {
        ArrayList<Medication> ml = new ArrayList<Medication>();
        // Create from CCR Medications
        if (ccr.getBody().getMedications() != null) {
            for (StructuredProductType med : ccr.getBody().getMedications().getMedication()) {
                ml.add(createMedication(med));
            }
        }
        // Create from CCR Immunizations
        if (ccr.getBody().getImmunizations() != null) {
            for (StructuredProductType imm : ccr.getBody().getImmunizations().getImmunization()) {
                ml.add(createMedication(imm));
            }
        }
        return ml;
    }

    /*
     * Creates a clinical model Medication from a CCR Structured Product
     */
    private Medication createMedication(StructuredProductType med) {
        Medication m = new Medication(med.getCCRDataObjectID());
        // Set the description of the Medication by adding the general CCR
        // description and the codedvalues of the product name and brand name
        if (med.getDescription() != null) {
            m.addDescription(convertToCodedValue(med.getDescription()));
        }
        // A CCR Medication may have multiple products
        for (Product pt : med.getProduct()) {
            m.addDescription(convertToCodedValue(pt.getProductName()));
            if (pt.getBrandName() != null) {
                m.addDescription(convertToCodedValue(pt.getBrandName()));
            }
        }
        // Set the type
        if (med.getType() != null) {
            m.setType(convertToCodedValue(med.getType()));
        }
        // Set the status
        if (med.getStatus() != null) {
            m.setStatus(convertToCodedValue(med.getStatus()));
        }
        // Set the dates associated with the medication
        try {
            String startDate = findDate(v.getTermSet("onset"), med.getDateTime());
            if (startDate != null) {
                m.setStarted(convertISO8601toSecfromEpoch(startDate));
            }
        } catch (NoValidDateFound ex) {
            Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
        }
        try {
            String stopDate = findDate(v.getTermSet("ended"), med.getDateTime());
            if (stopDate != null) {
                m.setStopped(convertISO8601toSecfromEpoch(stopDate));
            }
        } catch (NoValidDateFound ex) {
            Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
        }
        return m;
    }

    /*
     * Create clinical model Allergy objects from CCR Alerts
     */
    private ArrayList<Allergy> createAllergies() {
        ArrayList<Allergy> al = new ArrayList<Allergy>();
        if (ccr.getBody().getAlerts() != null) {
            for (AlertType at : ccr.getBody().getAlerts().getAlert()) {
                Allergy a = new Allergy(at.getCCRDataObjectID());
                // Set type
                if (at.getType() != null) {
                    a.setType(convertToCodedValue(at.getType()));
                }
                // Set the description using Alert <Description> and Alert <Agent>
                if (at.getDescription() != null) {
                    a.addDescription(convertToCodedValue(at.getDescription()));
                }
                for (Agent agt : at.getAgent()) {
                    // Walk through any Products and add to description
                    if (agt.getProducts() != null) {
                        for (StructuredProductType pt : agt.getProducts().getProduct()) {
                            if (pt.getDescription() != null) {
                                a.addDescription(convertToCodedValue(pt.getDescription()));
                            }
                            // A medication Product can be a collection of products
                            for (Product pdt : pt.getProduct()) {
                                a.addDescription(convertToCodedValue(pdt.getProductName()));
                                if (pdt.getBrandName() != null) {
                                    a.addDescription(convertToCodedValue(pdt.getBrandName()));
                                }
                            }
                        }
                    }
                    // Walk through the Environmental Agents as well
                    if (agt.getEnvironmentalAgents() != null) {
                        for (CCRCodedDataObjectType cdt : agt.getEnvironmentalAgents().getEnvironmentalAgent()) {
                            if (cdt.getDescription() != null) {
                                a.addDescription(convertToCodedValue(cdt.getDescription()));
                            }
                        }
                    }
                }
                try {
                    // Set Onset Date
                    String onsetDate = findDate(v.getTermSet("onset"), at.getDateTime());
                    if (onsetDate != null) {
                        a.setOnset(convertISO8601toSecfromEpoch(onsetDate));
                    }
                } catch (NoValidDateFound ex) {
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
                }
                al.add(a);
            }
        }
        return al;
    }

    /*
     * Create clinical model procedures from CCR Procedures
     */
    private ArrayList<Procedure> createProcedures() {
        // At this point a Procedure is just another encounter
        ArrayList<Procedure> pL = new ArrayList<Procedure>();
        if (ccr.getBody().getProcedures() != null) {
            for (ProcedureType pt : ccr.getBody().getProcedures().getProcedure()) {
                Procedure p = new Procedure(pt.getCCRDataObjectID());
                // Set type
                if (pt.getType() != null) {
                    p.setType(convertToCodedValue(pt.getType()));
                }
                // Set description
                if (pt.getDescription() != null) {
                    p.setDescription(convertToCodedValue(pt.getDescription()));
                }
                // Set the date the procedure occurred
                try {
                    String encounterDate = findDate(v.getTermSet("occurred"), pt.getDateTime());
                    if (encounterDate != null) {
                        p.setOccurred(convertISO8601toSecfromEpoch(encounterDate));
                    }
                } catch (NoValidDateFound ex) {
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
                }
                // Set the date the procedure ended on
                try {
                    String endDate = findDate(v.getTermSet("ended"), pt.getDateTime());
                    if (endDate != null) {
                        p.setEnded(convertISO8601toSecfromEpoch(endDate));
                    }
                } catch (NoValidDateFound ex) {
                    Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
                }
                // Set the list of practitioners involved in the procedure
                if (pt.getPractitioners() != null) {
                    for (ActorReferenceType art : pt.getPractitioners().getPractitioner()) {
                        p.addProvider(art.getActorID());
                    }
                }
                pL.add(p);
            }
        }

        return pL;
    }

    /*
     * Create clinical model Orders from CCR Orders. Also where clinical model
     * Goals are created.
     */
    private ArrayList<Order> createOrders() {
        ArrayList<Order> ol = new ArrayList<Order>();
        // There are three places for coded descriptions for orders
        // //Plan/Description  //Plan/OrderRequest/Description  //Plan/OrderRequest/*/Description
        // A JSON Order is equal to an OrderRequest, but need to push Plan date and description
        // down if not set in the OrderRequest.
        if (ccr.getBody().getPlanOfCare() != null) {
            for (PlanType pt : ccr.getBody().getPlanOfCare().getPlan()) {

                String planOrderDate = null;
                ArrayList<CodedValue> planDescription = new ArrayList<CodedValue>();
                ArrayList<CodedValue> planType = new ArrayList<CodedValue>();
                // Check for an ordered date for the <Plan>
                try {
                    planOrderDate = findDate(v.getTermSet("ordered"), pt.getDateTime());
                } catch (NoValidDateFound ex) {
                    //Logger.getLogger(RecordCreator.class.getName()).log(Level.INFO, "No ordered date found", ex);
                }
                // Check for a description for the <Plan>
                if (pt.getDescription() != null) {
                    planDescription.addAll(convertToCodedValue(pt.getDescription()));
                }
                // Set the type
                if (pt.getType() != null) {
                    planType.addAll(convertToCodedValue(pt.getType()));
                }
                // Now walk through each <OrderRequest> and create a clinical model Order
                for (PlanOfCareType pct : pt.getOrderRequest()) {
                    Order o = new Order(pt.getCCRDataObjectID());
                    // Check for an ordered date on the OrderRequest and if not found use <Plan> order date
                    try {
                        String orderDate = findDate(v.getTermSet("ordered"), pct.getDateTime());

                    } catch (NoValidDateFound ex) {
                        if (planOrderDate == null) {
                            Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
                        } else {
                            o.setOrderDate(convertISO8601toSecfromEpoch(planOrderDate));
                        }
                    }
                    // Add the <Plan> type to the Order type
                    o.addType(planType);
                    // Add the <Plan> description to the Order description
                    o.addDescription(planDescription);
                    // If the OrderRequest has a description add it to the Order as well
                    if (pct.getDescription() != null) {
                        o.addDescription(convertToCodedValue(pct.getDescription()));
                    }
                    // For each of the types of orderrequests, add to the Order
                    // Walk through Procedures
                    // TODO Add Procedure to Clinical Model
                    // Walk through Products
                    if (pct.getProducts() != null) {
                        for (StructuredProductType spt : pct.getProducts().getProduct()) {
                            o.addOrderRequest(createMedication(spt));
                        }
                    }
                    // Walk through Medications
                    if (pct.getMedications() != null) {
                        for (StructuredProductType spt : pct.getMedications().getMedication()) {
                            o.addOrderRequest(createMedication(spt));
                        }
                    }
                    // Walk through Immunizations
                    if (pct.getImmunizations() != null) {
                        for (StructuredProductType spt : pct.getImmunizations().getImmunization()) {
                            o.addOrderRequest(createMedication(spt));
                        }
                    }
                    // Walk through Services
                    if (pct.getServices() != null) {
                        for (EncounterType et : pct.getServices().getService()) {
                            o.addOrderRequest(createEncounter(et));
                        }
                    }
                    // Walk through Encounters
                    if (pct.getEncounters() != null) {
                        for (EncounterType et : pct.getEncounters().getEncounter()) {
                            o.addOrderRequest(createEncounter(et));
                        }
                    }
                    // TODO Skipped Authorizations

                    // Create the Goals for the order
                    if (pct.getGoals() != null) {
                        for (GoalType gt : pct.getGoals().getGoal()) {
                            Goal g = new Goal(gt.getCCRDataObjectID());
                            if (gt.getDescription() != null) {
                                g.setDescription(convertToCodedValue(gt.getDescription()));
                            }
                            if (gt.getType() != null) {
                                g.setType(convertToCodedValue(gt.getType()));
                            }
                            try {
                                String goalDate = findDate(v.getTermSet("onset"), gt.getDateTime());
                                if (goalDate != null) {
                                    g.setGoalDate(convertISO8601toSecfromEpoch(goalDate));
                                }
                            } catch (NoValidDateFound ex) {
                                Logger.getLogger(RecordCreator.class.getName()).log(Level.WARNING, ex.getMessage());
                            }
                            o.addGoal(g);
                        }
                    }
                    ol.add(o);
                }
            }
        }


        return ol;
    }

    /*
     * Converts a CCR coded items into a clinical model coded values
     */
    private ArrayList<CodedValue> convertToCodedValue(CodedDescriptionType cdt) {
        ArrayList<CodedValue> cvList = new ArrayList<CodedValue>();
        if (cdt == null) {
            return cvList;
        }
        // Set the <Text> field as a CodedValue
        if (cdt.getText() != null) {
            CodedValue cvText = new CodedValue();
            cvText.setCodingSystem("TEXT");
            cvText.addValue(cdt.getText());
            cvList.add(cvText);
        }

        // TODO combine codes with the same coding system
        // most CCRs representing patient data will not have multiple codes for
        // the same coding system

        // Create CodedValue for each code
        for (CodeType ct : cdt.getCode()) {
            CodedValue cv = new CodedValue();
            cv.setCodingSystem(ct.getCodingSystem());
            if (ct.getVersion() != null) {
                cv.setVersion(ct.getVersion());
            }
            cv.addValue(ct.getValue());
            cvList.add(cv);
        }
        return cvList;
    }

    /**
     * Determines if a CCR coded item is equal to any coded value in a list
     * @param cvList The list of coded value item to compare to
     * @param cdt The CCR coded item to compare against
     * @return <code>true</code> if there is a match, otherwise <code>false</code>
     */
    public static boolean codeMatch(ArrayList<CodedValue> cvList, CodedDescriptionType cdt) {
        // TODO Need to check for coding system.
        // Do not now becuase no overlap in SNOMED, ICD9, ICD10
        // Will need to leverage coding system names from CCR Validator and map
        //  to the names used by quality measures

        // Step through each coded value in the list
        for (CodedValue cv : cvList) {
            // For the coded value step through each value in the array
            for (String c : cv.getValues()) {
                // Compare the value to each code in the CCR coded item
                for (CodeType ct : cdt.getCode()) {
                    if (c.equals(ct.getValue())) {
                        return true;
                    }
                }
            }
        }
        // No code match found
        return false;
    }

    /*
     * Tries to find a particular date based on the supplied termset
     */
    private String findDate(TermSet type, List<DateTimeType> dateTime) throws NoValidDateFound {
        LOG.log(Level.FINEST, "Looking for date for: {0}", type.getId());
        // empty list throw error
        if (dateTime == null || dateTime.isEmpty()) {
            throw new NoValidDateFound("DateTime List was empty");
            // TODO Consider Defaulting to timestamp of the CCR
        }
        // if there is only one date, return it if we are looking for onset, occurred, or collected
        if (dateTime.size() == 1 && (type.getId().equals("onset") || type.getId().equals("occurred") || type.getId().equals("collected"))) {
            if (dateTime.get(0).getExactDateTime() != null) {
                return dateTime.get(0).getExactDateTime();
            }
            //throw new NoValidDateFound("No ExactDateTime Found");
            // TODO Not sure if should default to CCR timestamp
        } else {
            for (DateTimeType dt : dateTime) {
                // if there is a <Type> for the datetime check to see if it is
                // a concept match and if so return the datetime
                if (dt.getType() != null) {
                    if (isConceptMatch(type, dt.getType())) {
                        if (dt.getExactDateTime() != null) {
                            return dt.getExactDateTime();
                        }
                        //throw new NoValidDateFound("No ExactDateTime Found");
                    }
                }
            }
        }
        // No right type date found
        return null;
    }

    /*
     * This is like <code>codeMatch</code> method put checks the type string
     * against the term in the CCR coded item.  Typically there are not controlled
     * vocabularies for type or status for CCR data objects.
     */
    private boolean isConceptMatch(TermSet ts, CodedDescriptionType type) {
        // Check for matching codes
        Iterator<CodedValue> iCode = ts.getCodeIterator();
        while (iCode.hasNext()) {
            if (compareCodes(iCode.next(), type.getCode())) {
                return true;
            }
        }
        // No code match so check for matching terms
        Iterator<String> iTerm = ts.getTermIterator();
        while (iTerm.hasNext()) {
            if (compareTerms(iTerm.next(), type.getText())) {
                return true;
            }
        }
        // No code or term match, so no concept match
        return false;
    }

    /*
     * Compares the codes in the CodedValue with the list of CCR codes
     */
    private boolean compareCodes(CodedValue cv, List<CodeType> code) {
        // Step through each code in the CCR item
        for (CodeType ct : code) {
            // If it is using the same coding system check the codes
            if (isSameCodingSystem(cv, ct)) {
                // Check against each code value in the CodedValue
                for (String vCode : cv.getValues()) {
                    if (vCode.equals(ct.getValue())) {
                        return true;
                    }
                }
            }
        }
        // No code match
        return false;
    }

    /*
     * Simply compares the two terms
     * Separated out as a method so in future could plugin string normalization or
     *  other more complex comparasions using the UMLS and Semantic Network
     */
    private boolean compareTerms(String term, String text) {
        // At this point a simple non-case sensitive comparision
        boolean b = term.equalsIgnoreCase(text);
        LOG.log(Level.FINEST, "term [{0}] text [{1}]={2}", new Object[]{term, text, b});
        return b;

    }

    /*
     * In the real world it will require a little bit of coding to handle the
     * variations in naming of coding systems and the issues around versioning
     * of the coding systems.  
     */
    private boolean isSameCodingSystem(CodedValue cv, CodeType ct) {
        // TODO Not yet implemented (works okay now for ICD, SNOMED, RXNORM)
        // Need to check the codingsystem in the CCR code against the
        // correct TermSet based on cv.getCodingSystem()
        return true;
    }
}
