/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Kevin Leturc <kleturc@nuxeo.com>
 */
package org.nuxeo.ecm.core.trash;

import static org.nuxeo.ecm.core.trash.BulkTrashedStateChangeListener.PROCESS_CHILDREN_KEY;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.DocumentSecurityException;

/**
 * A {@link TrashService} implementation relying on {@code ecm:isTrashed}.
 *
 * @since 10.1
 */
public class PropertyTrashService extends AbstractTrashService {

    private static final Log log = LogFactory.getLog(PropertyTrashService.class);

    public static final String SYSPROP_IS_TRASHED = "isTrashed";

    @Override
    public boolean isTrashed(CoreSession session, DocumentRef docRef) {
        Boolean isTrashed = session.getDocumentSystemProp(docRef, SYSPROP_IS_TRASHED, Boolean.class);
        return Boolean.TRUE.equals(isTrashed);
    }

    @Override
    public void trashDocuments(List<DocumentModel> docs) {
        docs.forEach(this::doTrashDocument);
        docs.stream().map(DocumentModel::getCoreSession).findFirst().ifPresent(CoreSession::save);
    }

    protected void doTrashDocument(DocumentModel doc) {
        CoreSession session = doc.getCoreSession();
        DocumentRef docRef = doc.getRef();
        if (doc.isProxy()) {
            log.warn("Document " + doc.getId() + " of type " + doc.getType()
                    + " will be deleted immediately because it's a proxy");
            session.removeDocument(docRef);
        } else if (!session.canRemoveDocument(docRef)) {
            throw new DocumentSecurityException(
                    "User " + session.getPrincipal().getName() + " does not have the permission to remove the document "
                            + doc.getId() + " (" + doc.getPath() + ")");
        } else if (session.isTrashed(docRef)) {
            log.warn("Document " + doc.getId() + " of type " + doc.getType()
                    + " is already in the trash, nothing to do");
        } else {
            if (doc.getParentRef() == null) {
                // handle placeless document
                session.removeDocument(doc.getRef());
            } else {
                if (!Boolean.parseBoolean(String.valueOf(doc.getContextData(DISABLE_TRASH_RENAMING)))) {
                    String newName = mangleName(doc);
                    session.move(docRef, doc.getParentRef(), newName);
                }
                session.setDocumentSystemProp(docRef, SYSPROP_IS_TRASHED, Boolean.TRUE);
                notifyEvent(session, DOCUMENT_TRASHED, doc,
                        Collections.singletonMap(PROCESS_CHILDREN_KEY, Boolean.TRUE));
            }
        }
    }

    @Override
    public Set<DocumentRef> undeleteDocuments(List<DocumentModel> docs) {
        Set<DocumentRef> docRefs = new HashSet<>();
        for (DocumentModel doc : docs) {
            docRefs.addAll(doUntrashDocument(doc, true));
        }
        docs.stream().map(DocumentModel::getCoreSession).findFirst().ifPresent(CoreSession::save);
        return docRefs;
    }

    protected Set<DocumentRef> doUntrashDocument(DocumentModel doc, boolean processChildren) {
        CoreSession session = doc.getCoreSession();
        DocumentRef docRef = doc.getRef();

        // move only non placeless document
        // currently we don't trash placeless document
        DocumentRef parentRef = doc.getParentRef();
        if (!Boolean.TRUE.equals(doc.getContextData(DISABLE_TRASH_RENAMING)) && parentRef != null) {
            String newName = unmangleName(doc);
            if (!newName.equals(doc.getName())) {
                session.move(docRef, parentRef, newName);
            }
        }
        session.setDocumentSystemProp(docRef, SYSPROP_IS_TRASHED, Boolean.FALSE);
        notifyEvent(session, DOCUMENT_UNTRASHED, doc,
                Collections.singletonMap(PROCESS_CHILDREN_KEY, Boolean.valueOf(processChildren)));

        Set<DocumentRef> docRefs = new HashSet<>();
        docRefs.add(docRef);

        // now untrash the parent if needed
        if (parentRef != null && session.isTrashed(parentRef)) {
            Set<DocumentRef> ancestorDocRefs = doUntrashDocument(session.getDocument(parentRef), false);
            docRefs.addAll(ancestorDocRefs);
        }
        return docRefs;
    }

    @Override
    public boolean hasFeature(Feature feature) {
        switch (feature) {
            case TRASHED_STATE_IS_DEDUCED_FROM_LIFECYCLE:
            case TRASHED_STATE_IN_MIGRATION:
                return false;
            case TRASHED_STATE_IS_DEDICATED_PROPERTY:
                return true;
        default:
            throw new UnsupportedOperationException(feature.name());
        }
    }

}
