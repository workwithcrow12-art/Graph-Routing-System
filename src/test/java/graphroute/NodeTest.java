package graphroute;

import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NodeTest {
    @Test
    void validatesIdsAndGeographicCoordinates() {
        for (String id : new String[]{null, "", " "}) assertThrows(IllegalArgumentException.class, () -> new Node(id, "", 0, 0));
        for (double lat : new double[]{-91, 91, Double.NaN, Double.POSITIVE_INFINITY}) assertThrows(IllegalArgumentException.class, () -> new Node("n", "", lat, 0));
        for (double lon : new double[]{-181, 181, Double.NaN, Double.NEGATIVE_INFINITY}) assertThrows(IllegalArgumentException.class, () -> new Node("n", "", 0, lon));
        assertDoesNotThrow(() -> new Node("n", "", -90, 180));
    }

    @Test
    void preservesCoordinatesAndUsesStableIdIdentity() {
        Node node = new Node("a", null, 0, 0, 12, 34);
        assertEquals("", node.getName());
        assertEquals(0, node.getLat());
        assertEquals(0, node.getLng());
        assertTrue(node.hasScreenCoordinates());
        assertEquals(12, node.getX());
        assertEquals(34, node.getY());
        assertEquals(node, new Node("a", "other display name", 1, 1));
        assertEquals(node.hashCode(), new Node("a", "", 1, 1).hashCode());
        assertNotEquals(node, new Node("b", "", 0, 0));
    }

    @Test
    void domainFieldsAreImmutableWithoutAnimationState() {
        assertTrue(Modifier.isFinal(Node.class.getModifiers()));
        assertTrue(Arrays.stream(Node.class.getDeclaredFields()).allMatch(field -> Modifier.isFinal(field.getModifiers())));
        assertFalse(Arrays.stream(Node.class.getMethods()).anyMatch(method -> method.getName().startsWith("set") || method.getName().equals("getState")));
        assertThrows(IllegalArgumentException.class, () -> new Node("a", "", 0, 0, Double.NaN, 0));
    }
}
