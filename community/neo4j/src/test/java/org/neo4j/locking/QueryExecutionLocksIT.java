/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.locking;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.collection.primitive.PrimitiveLongResourceIterator;
import org.neo4j.cursor.Cursor;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Lock;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.ExecutionStatistics;
import org.neo4j.internal.kernel.api.ExplicitIndexRead;
import org.neo4j.internal.kernel.api.ExplicitIndexWrite;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.kernel.api.Locks;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.Procedures;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.SchemaRead;
import org.neo4j.internal.kernel.api.SchemaWrite;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.internal.kernel.api.TokenWrite;
import org.neo4j.internal.kernel.api.Write;
import org.neo4j.internal.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.internal.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.internal.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.internal.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.internal.kernel.api.exceptions.explicitindex.ExplicitIndexNotFoundKernelException;
import org.neo4j.internal.kernel.api.procs.ProcedureHandle;
import org.neo4j.internal.kernel.api.procs.ProcedureSignature;
import org.neo4j.internal.kernel.api.procs.QualifiedName;
import org.neo4j.internal.kernel.api.procs.UserFunctionHandle;
import org.neo4j.internal.kernel.api.procs.UserFunctionSignature;
import org.neo4j.internal.kernel.api.schema.SchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.constraints.ConstraintDescriptor;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.kernel.GraphDatabaseQueryService;
import org.neo4j.kernel.api.ExplicitIndexHits;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.ResourceTracker;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.dbms.DbmsOperations;
import org.neo4j.kernel.api.exceptions.RelationshipTypeIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.exceptions.index.IndexNotApplicableKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.IndexBrokenKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.api.schema.index.SchemaIndexDescriptor;
import org.neo4j.kernel.api.txstate.TxStateHolder;
import org.neo4j.kernel.impl.api.ClockContext;
import org.neo4j.kernel.impl.api.RelationshipVisitor;
import org.neo4j.kernel.impl.api.store.RelationshipIterator;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.impl.coreapi.PropertyContainerLocker;
import org.neo4j.kernel.impl.locking.ResourceTypes;
import org.neo4j.kernel.impl.query.Neo4jTransactionalContextFactory;
import org.neo4j.kernel.impl.query.QueryExecutionEngine;
import org.neo4j.kernel.impl.query.QueryExecutionKernelException;
import org.neo4j.kernel.impl.query.TransactionalContext;
import org.neo4j.kernel.impl.query.TransactionalContextFactory;
import org.neo4j.kernel.impl.query.clientconnection.ClientConnectionInfo;
import org.neo4j.kernel.impl.query.statistic.StatisticProvider;
import org.neo4j.register.Register;
import org.neo4j.storageengine.api.NodeItem;
import org.neo4j.storageengine.api.PropertyItem;
import org.neo4j.storageengine.api.RelationshipItem;
import org.neo4j.storageengine.api.Token;
import org.neo4j.storageengine.api.lock.ResourceType;
import org.neo4j.storageengine.api.schema.PopulationProgress;
import org.neo4j.test.rule.EmbeddedDatabaseRule;
import org.neo4j.values.storable.Value;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.neo4j.values.virtual.VirtualValues.EMPTY_MAP;

public class QueryExecutionLocksIT
{

    @Rule
    public EmbeddedDatabaseRule databaseRule = new EmbeddedDatabaseRule();

    @Test
    public void noLocksTakenForQueryWithoutAnyIndexesUsage() throws Exception
    {
        String query = "MATCH (n) return count(n)";
        List<LockOperationRecord> lockOperationRecords = traceQueryLocks( query );
        assertThat( "Observed list of lock operations is: " + lockOperationRecords,
                lockOperationRecords, is( empty() ) );
    }

    @Test
    public void takeLabelLockForQueryWithIndexUsages() throws Exception
    {
        String labelName = "Human";
        Label human = Label.label( labelName );
        String propertyKey = "name";
        createIndex( human, propertyKey );

        try ( Transaction transaction = databaseRule.beginTx() )
        {
            Node node = databaseRule.createNode( human );
            node.setProperty( propertyKey, RandomStringUtils.randomAscii( 10 ) );
            transaction.success();
        }

        String query = "MATCH (n:" + labelName + ") where n." + propertyKey + " = \"Fry\" RETURN n ";

        List<LockOperationRecord> lockOperationRecords = traceQueryLocks( query );
        assertThat( "Observed list of lock operations is: " + lockOperationRecords,
                lockOperationRecords, hasSize( 1 ) );

        LockOperationRecord operationRecord = lockOperationRecords.get( 0 );
        assertTrue( operationRecord.acquisition );
        assertFalse( operationRecord.exclusive );
        assertEquals( ResourceTypes.LABEL, operationRecord.resourceType );
    }

    @Test
    public void reTakeLabelLockForQueryWithIndexUsagesWhenSchemaStateWasUpdatedDuringLockOperations() throws Exception
    {
        String labelName = "Robot";
        Label robot = Label.label( labelName );
        String propertyKey = "name";
        createIndex( robot, propertyKey );

        try ( Transaction transaction = databaseRule.beginTx() )
        {
            Node node = databaseRule.createNode( robot );
            node.setProperty( propertyKey, RandomStringUtils.randomAscii( 10 ) );
            transaction.success();
        }

        String query = "MATCH (n:" + labelName + ") where n." + propertyKey + " = \"Bender\" RETURN n ";

        LockOperationListener lockOperationListener = new OnceSchemaFlushListener();
        List<LockOperationRecord> lockOperationRecords = traceQueryLocks( query, lockOperationListener );
        assertThat( "Observed list of lock operations is: " + lockOperationRecords,
                lockOperationRecords, hasSize( 3 ) );

        LockOperationRecord operationRecord = lockOperationRecords.get( 0 );
        assertTrue( operationRecord.acquisition );
        assertFalse( operationRecord.exclusive );
        assertEquals( ResourceTypes.LABEL, operationRecord.resourceType );

        LockOperationRecord operationRecord1 = lockOperationRecords.get( 1 );
        assertFalse( operationRecord1.acquisition );
        assertFalse( operationRecord1.exclusive );
        assertEquals( ResourceTypes.LABEL, operationRecord1.resourceType );

        LockOperationRecord operationRecord2 = lockOperationRecords.get( 2 );
        assertTrue( operationRecord2.acquisition );
        assertFalse( operationRecord2.exclusive );
        assertEquals( ResourceTypes.LABEL, operationRecord2.resourceType );
    }

    private void createIndex( Label label, String propertyKey )
    {
        try ( Transaction transaction = databaseRule.beginTx() )
        {
            databaseRule.schema().indexFor( label ).on( propertyKey ).create();
            transaction.success();
        }
        try ( Transaction ignored = databaseRule.beginTx() )
        {
            databaseRule.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
        }
    }

    private List<LockOperationRecord> traceQueryLocks( String query, LockOperationListener... listeners )
            throws QueryExecutionKernelException
    {
        GraphDatabaseQueryService graph = databaseRule.resolveDependency( GraphDatabaseQueryService.class );
        QueryExecutionEngine executionEngine = databaseRule.resolveDependency( QueryExecutionEngine.class );
        try ( InternalTransaction tx = graph
                .beginTransaction( KernelTransaction.Type.implicit, LoginContext.AUTH_DISABLED ) )
        {
            TransactionalContextWrapper context =
                    new TransactionalContextWrapper( createTransactionContext( graph, tx, query ), listeners );
            executionEngine.executeQuery( query, Collections.emptyMap(), context );
            return new ArrayList<>( context.recordingLocks.getLockOperationRecords() );
        }
    }

    private TransactionalContext createTransactionContext( GraphDatabaseQueryService graph, InternalTransaction tx,
            String query )
    {
        PropertyContainerLocker locker = new PropertyContainerLocker();
        TransactionalContextFactory contextFactory = Neo4jTransactionalContextFactory.create( graph, locker );
        return contextFactory.newContext( ClientConnectionInfo.EMBEDDED_CONNECTION, tx, query, EMPTY_MAP );
    }

    private static class TransactionalContextWrapper implements TransactionalContext
    {

        private final TransactionalContext delegate;
        private final List<LockOperationRecord> recordedLocks;
        private final LockOperationListener[] listeners;
        private LockRecordingReadOperationsWrapper recordingReadOperationsWrapper;
        private RecordingLocks recordingLocks;

        private TransactionalContextWrapper( TransactionalContext delegate, LockOperationListener... listeners )
        {
            this( delegate, new ArrayList<>(), listeners );
        }

        private TransactionalContextWrapper( TransactionalContext delegate, List<LockOperationRecord> recordedLocks, LockOperationListener... listeners )
        {
            this.delegate = delegate;
            this.recordedLocks = recordedLocks;
            this.listeners = listeners;
        }

        @Override
        public ExecutingQuery executingQuery()
        {
            return delegate.executingQuery();
        }

        @Override
        public DbmsOperations dbmsOperations()
        {
            return delegate.dbmsOperations();
        }

        @Override
        public KernelTransaction kernelTransaction()
        {
            if ( recordingLocks == null )
            {
                recordingLocks =
                        new RecordingLocks( delegate.kernelTransaction().locks(), asList( listeners ), recordedLocks );
            }
            return new DelegatingTransaction( delegate.kernelTransaction(), recordingLocks );
        }

        @Override
        public boolean isTopLevelTx()
        {
            return delegate.isTopLevelTx();
        }

        @Override
        public void close( boolean success )
        {
            delegate.close( success );
        }

        @Override
        public void terminate()
        {
            delegate.terminate();
        }

        @Override
        public void commitAndRestartTx()
        {
            delegate.commitAndRestartTx();
        }

        @Override
        public void cleanForReuse()
        {
            delegate.cleanForReuse();
        }

        @Override
        public boolean twoLayerTransactionState()
        {
            return delegate.twoLayerTransactionState();
        }

        @Override
        public TransactionalContext getOrBeginNewIfClosed()
        {
            if ( isOpen() )
            {
                return this;
            }
            else
            {
                return new TransactionalContextWrapper( delegate.getOrBeginNewIfClosed(), recordedLocks, listeners );
            }
        }

        @Override
        public boolean isOpen()
        {
            return delegate.isOpen();
        }

        @Override
        public GraphDatabaseQueryService graph()
        {
            return delegate.graph();
        }

        @Override
        public Statement statement()
        {
            return delegate.statement();
        }

        @Override
        public void check()
        {
            delegate.check();
        }

        @Override
        public TxStateHolder stateView()
        {
            return delegate.stateView();
        }

        @Override
        public Lock acquireWriteLock( PropertyContainer p )
        {
            return delegate.acquireWriteLock( p );
        }

        @Override
        public SecurityContext securityContext()
        {
            return delegate.securityContext();
        }

        @Override
        public StatisticProvider kernelStatisticProvider()
        {
            return delegate.kernelStatisticProvider();
        }

        @Override
        public KernelTransaction.Revertable restrictCurrentTransaction( SecurityContext context )
        {
            return delegate.restrictCurrentTransaction( context );
        }

        @Override
        public ResourceTracker resourceTracker()
        {
            return delegate.resourceTracker();
        }
    }

    private static class RecordingLocks implements Locks
    {
        private final Locks delegate;
        private final List<LockOperationListener> listeners;
        private final List<LockOperationRecord> lockOperationRecords;

        private RecordingLocks( Locks delegate,
                List<LockOperationListener> listeners,
                List<LockOperationRecord> lockOperationRecords )
        {
            this.delegate = delegate;
            this.listeners = listeners;
            this.lockOperationRecords = lockOperationRecords;
        }

        public List<LockOperationRecord> getLockOperationRecords()
        {
            return lockOperationRecords;
        }

        private void record( boolean exclusive, boolean acquisition, ResourceTypes type, long... ids )
        {
            if ( acquisition )
            {
                for ( LockOperationListener listener : listeners )
                {
                    listener.lockAcquired( exclusive, type, ids );
                }
            }
            lockOperationRecords.add( new LockOperationRecord( exclusive, acquisition, type, ids ) );
        }

        @Override
        public void acquireExclusiveNodeLock( long... ids )
        {
            record( true, true, ResourceTypes.NODE, ids );
            delegate.acquireExclusiveNodeLock( ids );
        }

        @Override
        public void acquireExclusiveRelationshipLock( long... ids )
        {
            record( true, true, ResourceTypes.RELATIONSHIP, ids );
            delegate.acquireExclusiveRelationshipLock( ids );
        }

        @Override
        public void acquireExclusiveExplicitIndexLock( long... ids )
        {
            record( true, true, ResourceTypes.EXPLICIT_INDEX, ids );
            delegate.acquireExclusiveExplicitIndexLock( ids );
        }

        @Override
        public void acquireExclusiveLabelLock( long... ids )
        {
            record( true, true, ResourceTypes.LABEL, ids );
            delegate.acquireExclusiveLabelLock( ids );
        }

        @Override
        public void releaseExclusiveNodeLock( long... ids )
        {
            record( true, false, ResourceTypes.NODE, ids );
            delegate.releaseExclusiveNodeLock( ids );
        }

        @Override
        public void releaseExclusiveRelationshipLock( long... ids )
        {
            record( true, false, ResourceTypes.RELATIONSHIP, ids );
            delegate.releaseExclusiveRelationshipLock( ids );
        }

        @Override
        public void releaseExclusiveExplicitIndexLock( long... ids )
        {
            record( true, false, ResourceTypes.EXPLICIT_INDEX, ids );
            delegate.releaseExclusiveExplicitIndexLock( ids );
        }

        @Override
        public void releaseExclusiveLabelLock( long... ids )
        {
            record( true, false, ResourceTypes.LABEL, ids );
            delegate.releaseExclusiveLabelLock( ids );
        }

        @Override
        public void acquireSharedNodeLock( long... ids )
        {
            record( false, true, ResourceTypes.NODE, ids );
            delegate.acquireSharedNodeLock( ids );
        }

        @Override
        public void acquireSharedRelationshipLock( long... ids )
        {
            record( false, true, ResourceTypes.RELATIONSHIP, ids );
            delegate.acquireSharedRelationshipLock( ids );
        }

        @Override
        public void acquireSharedExplicitIndexLock( long... ids )
        {
            record( false, true, ResourceTypes.EXPLICIT_INDEX, ids );
            delegate.acquireSharedExplicitIndexLock( ids );
        }

        @Override
        public void acquireSharedLabelLock( long... ids )
        {
            record( false, true, ResourceTypes.LABEL, ids );
            delegate.acquireSharedLabelLock( ids );
        }

        @Override
        public void releaseSharedNodeLock( long... ids )
        {
            record( false, false, ResourceTypes.NODE, ids );
            delegate.releaseSharedNodeLock( ids );
        }

        @Override
        public void releaseSharedRelationshipLock( long... ids )
        {
            record( false, false, ResourceTypes.RELATIONSHIP, ids );
            delegate.releaseSharedRelationshipLock( ids );
        }

        @Override
        public void releaseSharedExplicitIndexLock( long... ids )
        {
            record( false, false, ResourceTypes.EXPLICIT_INDEX, ids );
            delegate.releaseSharedExplicitIndexLock( ids );
        }

        @Override
        public void releaseSharedLabelLock( long... ids )
        {
            record( false, false, ResourceTypes.LABEL, ids );
            delegate.releaseSharedLabelLock( ids );
        }
    }

    private static class LockRecordingReadOperationsWrapper implements ReadOperations
    {
        private final List<LockOperationListener> listeners;
        private final List<LockOperationRecord> lockOperationRecords;
        private final ReadOperations readOperations;

        LockRecordingReadOperationsWrapper( ReadOperations readOperations, List<LockOperationRecord> recordedLocks, List<LockOperationListener> listeners )
        {
            this.listeners = listeners;
            this.readOperations = readOperations;
            this.lockOperationRecords = recordedLocks;
        }

        @Override
        public int labelGetForName( String labelName )
        {
            return readOperations.labelGetForName( labelName );
        }

        @Override
        public String labelGetName( int labelId ) throws LabelNotFoundKernelException
        {
            return readOperations.labelGetName( labelId );
        }

        @Override
        public Iterator<Token> labelsGetAllTokens()
        {
            return readOperations.labelsGetAllTokens();
        }

        @Override
        public int propertyKeyGetForName( String propertyKeyName )
        {
            return readOperations.propertyKeyGetForName( propertyKeyName );
        }

        @Override
        public String propertyKeyGetName( int propertyKeyId ) throws PropertyKeyIdNotFoundKernelException
        {
            return readOperations.propertyKeyGetName( propertyKeyId );
        }

        @Override
        public Iterator<Token> propertyKeyGetAllTokens()
        {
            return readOperations.propertyKeyGetAllTokens();
        }

        @Override
        public int relationshipTypeGetForName( String relationshipTypeName )
        {
            return readOperations.relationshipTypeGetForName( relationshipTypeName );
        }

        @Override
        public String relationshipTypeGetName( int relationshipTypeId ) throws RelationshipTypeIdNotFoundKernelException
        {
            return readOperations.relationshipTypeGetName( relationshipTypeId );
        }

        @Override
        public Iterator<Token> relationshipTypesGetAllTokens()
        {
            return readOperations.relationshipTypesGetAllTokens();
        }

        @Override
        public int labelCount()
        {
            return readOperations.labelCount();
        }

        @Override
        public int propertyKeyCount()
        {
            return readOperations.propertyKeyCount();
        }

        @Override
        public int relationshipTypeCount()
        {
            return readOperations.relationshipTypeCount();
        }

        @Override
        public PrimitiveLongResourceIterator nodesGetForLabel( int labelId )
        {
            return readOperations.nodesGetForLabel( labelId );
        }

        @Override
        public PrimitiveLongResourceIterator indexQuery( SchemaIndexDescriptor index, IndexQuery... predicates )
                throws IndexNotFoundKernelException, IndexNotApplicableKernelException
        {
            return readOperations.indexQuery( index, predicates );
        }

        @Override
        public PrimitiveLongIterator nodesGetAll()
        {
            return readOperations.nodesGetAll();
        }

        @Override
        public PrimitiveLongIterator relationshipsGetAll()
        {
            return readOperations.relationshipsGetAll();
        }

        @Override
        public RelationshipIterator nodeGetRelationships( long nodeId, Direction direction, int[] relTypes )
                throws EntityNotFoundException
        {
            return readOperations.nodeGetRelationships( nodeId, direction, relTypes );
        }

        @Override
        public RelationshipIterator nodeGetRelationships( long nodeId, Direction direction )
                throws EntityNotFoundException
        {
            return readOperations.nodeGetRelationships( nodeId, direction );
        }

        @Override
        public long nodeGetFromUniqueIndexSeek( SchemaIndexDescriptor index, IndexQuery.ExactPredicate... predicates )
                throws IndexNotFoundKernelException, IndexBrokenKernelException, IndexNotApplicableKernelException
        {
            return readOperations.nodeGetFromUniqueIndexSeek( index, predicates );
        }

        @Override
        public long nodesCountIndexed( SchemaIndexDescriptor index, long nodeId, Value value )
                throws IndexNotFoundKernelException, IndexBrokenKernelException
        {
            return readOperations.nodesCountIndexed( index, nodeId, value );
        }

        @Override
        public boolean nodeExists( long nodeId )
        {
            return readOperations.nodeExists( nodeId );
        }

        @Override
        public boolean nodeHasLabel( long nodeId, int labelId ) throws EntityNotFoundException
        {
            return readOperations.nodeHasLabel( nodeId, labelId );
        }

        @Override
        public int nodeGetDegree( long nodeId, Direction direction, int relType ) throws EntityNotFoundException
        {
            return readOperations.nodeGetDegree( nodeId, direction, relType );
        }

        @Override
        public int nodeGetDegree( long nodeId, Direction direction ) throws EntityNotFoundException
        {
            return readOperations.nodeGetDegree( nodeId, direction );
        }

        @Override
        public boolean nodeIsDense( long nodeId ) throws EntityNotFoundException
        {
            return readOperations.nodeIsDense( nodeId );
        }

        @Override
        public PrimitiveIntIterator nodeGetLabels( long nodeId ) throws EntityNotFoundException
        {
            return readOperations.nodeGetLabels( nodeId );
        }

        @Override
        public PrimitiveIntIterator nodeGetPropertyKeys( long nodeId ) throws EntityNotFoundException
        {
            return readOperations.nodeGetPropertyKeys( nodeId );
        }

        @Override
        public PrimitiveIntIterator relationshipGetPropertyKeys( long relationshipId ) throws EntityNotFoundException
        {
            return readOperations.relationshipGetPropertyKeys( relationshipId );
        }

        @Override
        public PrimitiveIntIterator graphGetPropertyKeys()
        {
            return readOperations.graphGetPropertyKeys();
        }

        @Override
        public PrimitiveIntIterator nodeGetRelationshipTypes( long nodeId ) throws EntityNotFoundException
        {
            return readOperations.nodeGetRelationshipTypes( nodeId );
        }

        @Override
        public boolean nodeHasProperty( long nodeId, int propertyKeyId ) throws EntityNotFoundException
        {
            return readOperations.nodeHasProperty( nodeId, propertyKeyId );
        }

        @Override
        public Value nodeGetProperty( long nodeId, int propertyKeyId ) throws EntityNotFoundException
        {
            return readOperations.nodeGetProperty( nodeId, propertyKeyId );
        }

        @Override
        public boolean relationshipHasProperty( long relationshipId, int propertyKeyId ) throws EntityNotFoundException
        {
            return readOperations.relationshipHasProperty( relationshipId, propertyKeyId );
        }

        @Override
        public Value relationshipGetProperty( long relationshipId, int propertyKeyId ) throws EntityNotFoundException
        {
            return readOperations.relationshipGetProperty( relationshipId, propertyKeyId );
        }

        @Override
        public boolean graphHasProperty( int propertyKeyId )
        {
            return readOperations.graphHasProperty( propertyKeyId );
        }

        @Override
        public Value graphGetProperty( int propertyKeyId )
        {
            return readOperations.graphGetProperty( propertyKeyId );
        }

        @Override
        public <EXCEPTION extends Exception> void relationshipVisit( long relId,
                RelationshipVisitor<EXCEPTION> visitor ) throws EntityNotFoundException, EXCEPTION
        {
            readOperations.relationshipVisit( relId, visitor );
        }

        @Override
        public long nodesGetCount()
        {
            return readOperations.nodesGetCount();
        }

        @Override
        public long relationshipsGetCount()
        {
            return readOperations.relationshipsGetCount();
        }

        @Override
        public Cursor<NodeItem> nodeCursorById( long nodeId ) throws EntityNotFoundException
        {
            return readOperations.nodeCursorById( nodeId );
        }

        @Override
        public Cursor<RelationshipItem> relationshipCursorById( long relId ) throws EntityNotFoundException
        {
            return readOperations.relationshipCursorById( relId );
        }

        @Override
        public Cursor<PropertyItem> nodeGetProperties( NodeItem node )
        {
            return readOperations.nodeGetProperties( node );
        }

        @Override
        public Cursor<PropertyItem> relationshipGetProperties( RelationshipItem relationship )
        {
            return readOperations.relationshipGetProperties( relationship );
        }

        @Override
        public SchemaIndexDescriptor indexGetForSchema( SchemaDescriptor descriptor ) throws SchemaRuleNotFoundException
        {
            return readOperations.indexGetForSchema( descriptor );
        }

        @Override
        public Iterator<SchemaIndexDescriptor> indexesGetForLabel( int labelId )
        {
            return readOperations.indexesGetForLabel( labelId );
        }

        @Override
        public Iterator<SchemaIndexDescriptor> indexesGetAll()
        {
            return readOperations.indexesGetAll();
        }

        @Override
        public InternalIndexState indexGetState( SchemaIndexDescriptor descriptor ) throws IndexNotFoundKernelException
        {
            return readOperations.indexGetState( descriptor );
        }

        @Override
        public IndexProvider.Descriptor indexGetProviderDescriptor( SchemaIndexDescriptor descriptor ) throws IndexNotFoundKernelException
        {
            return readOperations.indexGetProviderDescriptor( descriptor );
        }

        @Override
        public PopulationProgress indexGetPopulationProgress( SchemaIndexDescriptor descriptor )
                throws IndexNotFoundKernelException
        {
            return readOperations.indexGetPopulationProgress( descriptor );
        }

        @Override
        public long indexSize( SchemaIndexDescriptor descriptor ) throws IndexNotFoundKernelException
        {
            return readOperations.indexSize( descriptor );
        }

        @Override
        public double indexUniqueValuesSelectivity( SchemaIndexDescriptor descriptor ) throws IndexNotFoundKernelException
        {
            return readOperations.indexUniqueValuesSelectivity( descriptor );
        }

        @Override
        public String indexGetFailure( SchemaIndexDescriptor descriptor ) throws IndexNotFoundKernelException
        {
            return readOperations.indexGetFailure( descriptor );
        }

        @Override
        public Iterator<ConstraintDescriptor> constraintsGetForSchema( SchemaDescriptor descriptor )
        {
            return readOperations.constraintsGetForSchema( descriptor );
        }

        @Override
        public Iterator<ConstraintDescriptor> constraintsGetForLabel( int labelId )
        {
            return readOperations.constraintsGetForLabel( labelId );
        }

        @Override
        public Iterator<ConstraintDescriptor> constraintsGetForRelationshipType( int typeId )
        {
            return readOperations.constraintsGetForRelationshipType( typeId );
        }

        @Override
        public Iterator<ConstraintDescriptor> constraintsGetAll()
        {
            return readOperations.constraintsGetAll();
        }

        @Override
        public Long indexGetOwningUniquenessConstraintId( SchemaIndexDescriptor index )
        {
            return readOperations.indexGetOwningUniquenessConstraintId( index );
        }

        @Override
        public <K, V> V schemaStateGetOrCreate( K key, Function<K,V> creator )
        {
            return readOperations.schemaStateGetOrCreate( key, creator );
        }

        @Override
        public <K, V> V schemaStateGet( K key )
        {
            return readOperations.schemaStateGet( key );
        }

        @Override
        public void schemaStateFlush()
        {
            readOperations.schemaStateFlush();
        }

        @Override
        public void acquireExclusive( ResourceType type, long... ids )
        {
            for ( LockOperationListener listener : listeners )
            {
                listener.lockAcquired( true, type, ids );
            }
            lockOperationRecords.add( new LockOperationRecord( true, true, type, ids ) );
            readOperations.acquireExclusive( type, ids );
        }

        @Override
        public void acquireShared( ResourceType type, long... ids )
        {
            for ( LockOperationListener listener : listeners )
            {
                listener.lockAcquired( false, type, ids );
            }
            lockOperationRecords.add( new LockOperationRecord( false, true, type, ids ) );
            readOperations.acquireShared( type, ids );
        }

        @Override
        public void releaseExclusive( ResourceType type, long... ids )
        {
            lockOperationRecords.add( new LockOperationRecord( true, false, type, ids ) );
            readOperations.releaseExclusive( type, ids );
        }

        @Override
        public void releaseShared( ResourceType type, long... ids )
        {
            lockOperationRecords.add( new LockOperationRecord( false, false, type, ids ) );
            readOperations.releaseShared( type, ids );
        }

        @Override
        public boolean nodeExplicitIndexExists( String indexName, Map<String,String> customConfiguration )
        {
            return readOperations.nodeExplicitIndexExists( indexName, customConfiguration );
        }

        @Override
        public boolean relationshipExplicitIndexExists( String indexName, Map<String,String> customConfiguration )
        {
            return readOperations.relationshipExplicitIndexExists( indexName, customConfiguration );
        }

        @Override
        public Map<String,String> nodeExplicitIndexGetConfiguration( String indexName )
                throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.nodeExplicitIndexGetConfiguration( indexName );
        }

        @Override
        public Map<String,String> relationshipExplicitIndexGetConfiguration( String indexName )
                throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.relationshipExplicitIndexGetConfiguration( indexName );
        }

        @Override
        public ExplicitIndexHits nodeExplicitIndexGet( String indexName, String key, Object value )
                throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.nodeExplicitIndexGet( indexName, key, value );
        }

        @Override
        public ExplicitIndexHits nodeExplicitIndexQuery( String indexName, String key, Object queryOrQueryObject )
                throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.nodeExplicitIndexQuery( indexName, key, queryOrQueryObject );
        }

        @Override
        public ExplicitIndexHits nodeExplicitIndexQuery( String indexName, Object queryOrQueryObject )
                throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.nodeExplicitIndexQuery( indexName, queryOrQueryObject );
        }

        @Override
        public ExplicitIndexHits relationshipExplicitIndexGet( String name, String key, Object valueOrNull, long startNode,
                long endNode ) throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.relationshipExplicitIndexGet( name, key, valueOrNull, startNode, endNode );
        }

        @Override
        public ExplicitIndexHits relationshipExplicitIndexQuery( String indexName, String key, Object queryOrQueryObject,
                long startNode, long endNode ) throws ExplicitIndexNotFoundKernelException
        {
            return readOperations
                    .relationshipExplicitIndexQuery( indexName, key, queryOrQueryObject, startNode, endNode );
        }

        @Override
        public ExplicitIndexHits relationshipExplicitIndexQuery( String indexName, Object queryOrQueryObject,
                long startNode, long endNode ) throws ExplicitIndexNotFoundKernelException
        {
            return readOperations.relationshipExplicitIndexQuery( indexName, queryOrQueryObject, startNode, endNode );
        }

        @Override
        public String[] nodeExplicitIndexesGetAll()
        {
            return readOperations.nodeExplicitIndexesGetAll();
        }

        @Override
        public String[] relationshipExplicitIndexesGetAll()
        {
            return readOperations.relationshipExplicitIndexesGetAll();
        }

        @Override
        public long countsForNode( int labelId )
        {
            return readOperations.countsForNode( labelId );
        }

        @Override
        public long countsForNodeWithoutTxState( int labelId )
        {
            return readOperations.countsForNodeWithoutTxState( labelId );
        }

        @Override
        public long countsForRelationship( int startLabelId, int typeId, int endLabelId )
        {
            return readOperations.countsForRelationship( startLabelId, typeId, endLabelId );
        }

        @Override
        public long countsForRelationshipWithoutTxState( int startLabelId, int typeId, int endLabelId )
        {
            return readOperations.countsForRelationshipWithoutTxState( startLabelId, typeId, endLabelId );
        }

        @Override
        public Register.DoubleLongRegister indexUpdatesAndSize( SchemaIndexDescriptor index,
                Register.DoubleLongRegister target ) throws IndexNotFoundKernelException
        {
            return readOperations.indexUpdatesAndSize( index, target );
        }

        @Override
        public Register.DoubleLongRegister indexSample( SchemaIndexDescriptor index, Register.DoubleLongRegister target )
                throws IndexNotFoundKernelException
        {
            return readOperations.indexSample( index, target );
        }

        @Override
        public ProcedureHandle procedureGet( QualifiedName name ) throws ProcedureException
        {
            return readOperations.procedureGet( name );
        }

        @Override
        public UserFunctionHandle functionGet( QualifiedName name )
        {
            return readOperations.functionGet( name );
        }

        @Override
        public UserFunctionHandle aggregationFunctionGet( QualifiedName name )
        {
            return readOperations.aggregationFunctionGet( name );
        }

        @Override
        public Set<UserFunctionSignature> functionsGetAll()
        {
            return readOperations.functionsGetAll();
        }

        @Override
        public Set<ProcedureSignature> proceduresGetAll()
        {
            return readOperations.proceduresGetAll();
        }

        List<LockOperationRecord> getLockOperationRecords()
        {
            return lockOperationRecords;
        }
    }

    private static class LockOperationListener implements EventListener
    {
        void lockAcquired( boolean exclusive, ResourceType resourceType, long... ids )
        {
            // empty operation
        }
    }

    private static class LockOperationRecord
    {
        private final boolean exclusive;
        private final boolean acquisition;
        private final ResourceType resourceType;
        private final long[] ids;

        LockOperationRecord( boolean exclusive, boolean acquisition, ResourceType resourceType, long[] ids )
        {
            this.exclusive = exclusive;
            this.acquisition = acquisition;
            this.resourceType = resourceType;
            this.ids = ids;
        }

        @Override
        public String toString()
        {
            return "LockOperationRecord{" + "exclusive=" + exclusive + ", acquisition=" + acquisition +
                    ", resourceType=" + resourceType + ", ids=" + Arrays.toString( ids ) + '}';
        }
    }

    private class OnceSchemaFlushListener extends LockOperationListener
    {
        private boolean executed;

        @Override
        void lockAcquired( boolean exclusive, ResourceType resourceType, long... ids )
        {
            if ( !executed )
            {
                ThreadToStatementContextBridge bridge =
                        databaseRule.resolveDependency( ThreadToStatementContextBridge.class );
                KernelTransaction ktx =
                        bridge.getKernelTransactionBoundToThisThread( true );
                ktx.schemaRead().schemaStateFlush();
            }
            executed = true;
        }
    }

    private static class DelegatingTransaction implements KernelTransaction
    {
        private final KernelTransaction internal;
        private final Locks locks;

        DelegatingTransaction( KernelTransaction internal, Locks locks )
        {
            this.internal = internal;
            this.locks = locks;
        }

        @Override
        public void success()
        {
            internal.success();
        }

        @Override
        public void failure()
        {
            internal.failure();
        }

        @Override
        public Read dataRead()
        {
            return internal.dataRead();
        }

        @Override
        public Read stableDataRead()
        {
            return internal.stableDataRead();
        }

        @Override
        public void markAsStable()
        {
            internal.markAsStable();
        }

        @Override
        public Write dataWrite() throws InvalidTransactionTypeKernelException
        {
            return internal.dataWrite();
        }

        @Override
        public ExplicitIndexRead indexRead()
        {
            return internal.indexRead();
        }

        @Override
        public ExplicitIndexWrite indexWrite() throws InvalidTransactionTypeKernelException
        {
            return internal.indexWrite();
        }

        @Override
        public TokenRead tokenRead()
        {
            return internal.tokenRead();
        }

        @Override
        public TokenWrite tokenWrite()
        {
            return internal.tokenWrite();
        }

        @Override
        public org.neo4j.internal.kernel.api.Token token()
        {
            return internal.token();
        }

        @Override
        public SchemaRead schemaRead()
        {
            return internal.schemaRead();
        }

        @Override
        public SchemaWrite schemaWrite() throws InvalidTransactionTypeKernelException
        {
            return internal.schemaWrite();
        }

        @Override
        public Locks locks()
        {
            return locks;
        }

        @Override
        public CursorFactory cursors()
        {
            return internal.cursors();
        }

        @Override
        public Procedures procedures()
        {
            return internal.procedures();
        }

        @Override
        public ExecutionStatistics executionStatistics()
        {
            return internal.executionStatistics();
        }

        @Override
        public Statement acquireStatement()
        {
            return internal.acquireStatement();
        }

        @Override
        public long closeTransaction() throws TransactionFailureException
        {
            return internal.closeTransaction();
        }

        @Override
        public void close() throws TransactionFailureException
        {
            internal.close();
        }

        @Override
        public boolean isOpen()
        {
            return internal.isOpen();
        }

        @Override
        public SecurityContext securityContext()
        {
            return internal.securityContext();
        }

        @Override
        public Optional<Status> getReasonIfTerminated()
        {
            return internal.getReasonIfTerminated();
        }

        @Override
        public boolean isTerminated()
        {
            return internal.isTerminated();
        }

        @Override
        public void markForTermination( Status reason )
        {
            internal.markForTermination( reason );
        }

        @Override
        public long lastTransactionTimestampWhenStarted()
        {
            return internal.lastTransactionTimestampWhenStarted();
        }

        @Override
        public long lastTransactionIdWhenStarted()
        {
            return internal.lastTransactionIdWhenStarted();
        }

        @Override
        public long startTime()
        {
            return internal.startTime();
        }

        @Override
        public long timeout()
        {
            return internal.timeout();
        }

        @Override
        public void registerCloseListener( CloseListener listener )
        {
            internal.registerCloseListener( listener );
        }

        @Override
        public Type transactionType()
        {
            return internal.transactionType();
        }

        @Override
        public long getTransactionId()
        {
            return internal.getTransactionId();
        }

        @Override
        public long getCommitTime()
        {
            return internal.getCommitTime();
        }

        @Override
        public Revertable overrideWith( SecurityContext context )
        {
            return internal.overrideWith( context );
        }

        @Override
        public ClockContext clocks()
        {
            return internal.clocks();
        }

        @Override
        public NodeCursor nodeCursor()
        {
            return internal.nodeCursor();
        }

        @Override
        public RelationshipScanCursor relationshipCursor()
        {
            return internal.relationshipCursor();
        }

        @Override
        public PropertyCursor propertyCursor()
        {
            return internal.propertyCursor();
        }

        @Override
        public void assertOpen()
        {
            internal.assertOpen();
        }
    }
}
