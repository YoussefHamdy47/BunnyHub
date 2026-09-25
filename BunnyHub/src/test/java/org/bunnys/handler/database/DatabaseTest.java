package org.bunnys.handler.database;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import org.bson.*;
import org.bson.codecs.*;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bunnys.database.models.user.BunnyUser;
import org.junit.jupiter.api.Test;
import static org.bson.codecs.configuration.CodecRegistries.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseTest {
    @Test void codecReadsLegacyIntRpAndRetainsExistingFieldNames() {
        var registry = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                fromProviders(PojoCodecProvider.builder().automatic(true).build()));
        var codec = registry.get(BunnyUser.class);
        var user = codec.decode(new BsonDocumentReader(BsonDocument.parse(
                "{userID:'123', Rank:2, RP:1234, Subjects:[]}")), DecoderContext.builder().build());
        assertEquals(1234L, user.getRp());
        user.setRp((long) Integer.MAX_VALUE + 1);
        var encoded = new BsonDocument();
        codec.encode(new BsonDocumentWriter(encoded), user, EncoderContext.builder().build());
        assertEquals((long) Integer.MAX_VALUE + 1, encoded.getInt64("RP").getValue());
        assertTrue(encoded.containsKey("Rank"));
        assertTrue(encoded.containsKey("Subjects"));
    }

    @Test void staleRevisionDoesNotUpsertOrClobberNewerData() {
        MongoManager manager = mock(MongoManager.class);
        @SuppressWarnings("unchecked") MongoCollection<BunnyUser> collection = mock(MongoCollection.class);
        when(manager.getDatabase()).thenReturn(mock(MongoDatabase.class));
        when(manager.getCollection(BunnyUser.class, "BunnyUsers")).thenReturn(collection);
        when(collection.replaceOne(any(Bson.class), any(BunnyUser.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        DB.init(manager);
        BunnyUser user = new BunnyUser();
        user.setRevision(7L);
        assertThrows(IllegalStateException.class,
                () -> DB.save(BunnyUser.class, "BunnyUsers", Filters.eq("userID", "123"), user));
        assertEquals(7L, user.getRevision());
        verify(collection).replaceOne(any(Bson.class), same(user));
        verifyNoMoreInteractions(collection);
        DB.init(null);
    }
}
