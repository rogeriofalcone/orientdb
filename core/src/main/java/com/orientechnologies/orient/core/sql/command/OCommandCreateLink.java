/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.sql.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.ORecordLazyList;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.metadata.security.ODatabaseSecurityResources;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentHelper;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.parser.OSQLParser;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.core.type.tree.OMVRBTreeRIDSet;
import static com.orientechnologies.orient.core.sql.parser.SQLGrammarUtils.*;

/**
 * SQL CREATE LINK command: Transform a JOIN relationship to a physical LINK
 * 
 * @author Luca Garulli
 * @author Johann Sorel (Geomatys)
 */
public class OCommandCreateLink extends OCommandAbstract implements OCommandDistributedReplicateRequest{
  public static final String  KEYWORD_CREATE = "CREATE";
  public static final String  KEYWORD_LINK   = "LINK";
  private static final String KEYWORD_FROM   = "FROM";
  private static final String KEYWORD_TO     = "TO";
  private static final String KEYWORD_TYPE   = "TYPE";

  private String destClassName;
  private String destField;
  private String sourceClassName;
  private String sourceField;
  private String linkName;
  private OType linkType;
  private boolean inverse = false;
  
  public OCommandCreateLink() {
  }

  public OCommandCreateLink parse(final OCommandRequest iRequest) {    
    final ODatabaseRecord database = getDatabase();
    database.checkSecurity(ODatabaseSecurityResources.COMMAND, ORole.PERMISSION_READ);

    final OSQLParser.CommandCreateLinkContext candidate = getCommand(iRequest, OSQLParser.CommandCreateLinkContext.class);
    
    int i = 0;
    
    if(candidate.linkName() != null){
      linkName = candidate.linkName().getText();
    }
    if(candidate.TYPE() != null){
      linkType = OType.valueOf(visitAsString(candidate.reference(i++)).toUpperCase(Locale.ENGLISH));
    }
    
    sourceClassName = visitAsString(candidate.reference(i++));
    sourceField = visitAsString(candidate.reference(i++));
    destClassName = visitAsString(candidate.reference(i++));
    destField = visitAsString(candidate.reference(i++));
    
    inverse = candidate.INVERSE() != null ;
    
    return this;
  }
  
  
  /**
   * Execute the CREATE LINK.
   */
  public Object execute(final Map<Object, Object> iArgs) {
    if (destField == null)
      throw new OCommandExecutionException("Cannot execute the command because it has not been parsed yet");

    final ODatabaseRecord database = getDatabase();
    if (!(database.getDatabaseOwner() instanceof ODatabaseDocumentTx))
      throw new OCommandSQLParsingException("This command supports only the database type ODatabaseDocumentTx and type '"
          + database.getClass() + "' was found");

    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getDatabaseOwner();

    final OClass sourceClass = database.getMetadata().getSchema().getClass(sourceClassName);
    if (sourceClass == null)
      throw new OCommandExecutionException("Source class '" + sourceClassName + "' not found");

    final OClass destClass = database.getMetadata().getSchema().getClass(destClassName);
    if (destClass == null)
      throw new OCommandExecutionException("Destination class '" + destClassName + "' not found");

    Object value;
    
    String cmd = "select from ";
    if (!ODocumentHelper.ATTRIBUTE_RID.equals(destField)) {
    	cmd = "select from " + destClassName + " where " + destField + " = ";
    }
    
    List<ODocument> result;
    ODocument target;
    Object oldValue;
    long total = 0;

    if (linkName == null)
      // NO LINK NAME EXPRESSED: OVERWRITE THE SOURCE FIELD
      linkName = sourceField;

    boolean multipleRelationship;
    if (linkType != null)
      // DETERMINE BASED ON FORCED TYPE
      multipleRelationship = linkType == OType.LINKSET || linkType == OType.LINKLIST;
    else
      multipleRelationship = false;

    long totRecords = db.countClass(sourceClass.getName());
    long currRecord = 0;

    if (progressListener != null)
      progressListener.onBegin(this, totRecords);

    database.declareIntent(new OIntentMassiveInsert());
    try {
      // BROWSE ALL THE RECORDS OF THE SOURCE CLASS
      for (ODocument doc : db.browseClass(sourceClass.getName())) {
        doc.unpin();

        value = doc.field(sourceField);

        if (value != null) {
          if (value instanceof ODocument || value instanceof ORID) {
            // ALREADY CONVERTED
          } else if (value instanceof Collection<?>) {
            // TODO
          } else {
            // SEARCH THE DESTINATION RECORD
            target = null;

            if (!ODocumentHelper.ATTRIBUTE_RID.equals(destField) && value instanceof String)
              if (((String) value).length() == 0)
                value = null;
              else
                value = "'" + value + "'";

            result = database.<OCommandRequest> command(new OSQLSynchQuery<ODocument>(cmd + value)).execute();

            if (result == null || result.size() == 0)
              value = null;
            else if (result.size() > 1)
              throw new OCommandExecutionException("Cannot create link because multiple records was found in class '"
                  + destClass.getName() + "' with value " + value + " in field '" + destField + "'");
            else {
              target = result.get(0);
              value = target;
            }

            if (target != null && inverse) {
              // INVERSE RELATIONSHIP
              oldValue = target.field(linkName);

              if (oldValue != null) {
                if (!multipleRelationship)
                  multipleRelationship = true;

                Collection<ODocument> coll;
                if (oldValue instanceof Collection) {
                  // ADD IT IN THE EXISTENT COLLECTION
                  coll = (Collection<ODocument>) oldValue;
                  target.setDirty();
                } else {
                  // CREATE A NEW COLLECTION FOR BOTH
                  coll = new ArrayList<ODocument>(2);
                  target.field(linkName, coll);
                  coll.add((ODocument) oldValue);
                }
                coll.add(doc);
              } else {
                if (linkType != null)
                  if (linkType == OType.LINKSET) {
                    value = new OMVRBTreeRIDSet(target);
                    ((OMVRBTreeRIDSet) value).add(doc);
                  } else if (linkType == OType.LINKLIST) {
                    value = new ORecordLazyList(target);
                    ((ORecordLazyList) value).add(doc);
                  } else
                    // IGNORE THE TYPE, SET IT AS LINK
                    value = doc;
                else
                  value = doc;

                target.field(linkName, value);
              }
              target.save();

            } else {
              // SET THE REFERENCE
              doc.field(linkName, value);
              doc.save();
            }

            total++;
          }
        }

        if (progressListener != null)
          progressListener.onProgress(this, currRecord, currRecord * 100f / totRecords);
      }

      if (total > 0) {
        if (inverse) {
          // REMOVE THE OLD PROPERTY IF ANY
          OProperty prop = destClass.getProperty(linkName);
          if (prop != null)
            destClass.dropProperty(linkName);

          if (linkType == null)
            linkType = multipleRelationship ? OType.LINKSET : OType.LINK;

          // CREATE THE PROPERTY
          destClass.createProperty(linkName, linkType, sourceClass);

        } else {

          // REMOVE THE OLD PROPERTY IF ANY
          OProperty prop = sourceClass.getProperty(linkName);
          if (prop != null)
            sourceClass.dropProperty(linkName);

          // CREATE THE PROPERTY
          sourceClass.createProperty(linkName, OType.LINK, destClass);
        }
      }

      if (progressListener != null)
        progressListener.onCompletition(this, true);

    } catch (Exception e) {
      if (progressListener != null)
        progressListener.onCompletition(this, false);

      throw new OCommandExecutionException("Error on creation of links", e);

    } finally {
      database.declareIntent(null);
    }

    return total;
  }

  @Override
  public String getSyntax() {
    return "CREATE LINK <link-name> [TYPE <link-type>] FROM <source-class>.<source-property> TO <destination-class>.<destination-property> [INVERSE]";
  }
  
}