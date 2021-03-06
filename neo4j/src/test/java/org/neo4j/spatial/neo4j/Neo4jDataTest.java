package org.neo4j.spatial.neo4j;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.spatial.core.CRS;
import org.neo4j.spatial.core.Polygon;
import org.neo4j.spatial.core.Polyline;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.values.storable.CoordinateReferenceSystem;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.storable.Values;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

public class Neo4jDataTest {
    private DatabaseManagementService databases;
    private GraphDatabaseService db;

    @Before
    public void setUp() {
        databases = new TestDatabaseManagementServiceBuilder().impermanent().build();
        db = databases.database(DEFAULT_DATABASE_NAME);
    }

    @After
    public void tearDown() {
        databases.shutdown();
    }

    @Test
    public void shouldUnderstandNeo4jPointAsPoint() {
        Neo4jPoint neo4jPoint;

        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode(Label.label("PoI"));
            node.setProperty("location", Values.pointValue(CoordinateReferenceSystem.Cartesian, 5.3, 9.1));
            neo4jPoint = new Neo4jPoint(node);
            assertThat("expected Neo4jPoint to contain correct coordinates", neo4jPoint.getCoordinate(), equalTo(new double[]{5.3, 9.1}));
            tx.commit();
        }

    }

    @Test
    public void shouldUnderstandPropertyAsPoint() {
        PropertyPoint propertyPoint;

        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode(Label.label("Location"));
            node.setProperty("x", 5.3);
            node.setProperty("y", 9.1);
            propertyPoint = new PropertyPoint(node, "x", "y");
            tx.commit();
        }

        assertThat("expected PropertyPoint to contain correct coordinates", propertyPoint.getCoordinate(), equalTo(new double[]{5.3, 9.1}));
    }

    @Test
    public void shouldUnderstandPropertyArrayAsSimplePolygon() {
        Polygon.SimplePolygon simplePolygon;

        try (Transaction tx = db.beginTx()) {
            Node node = tx.createNode(Label.label("Building"));
            node.setProperty("polygon", new Point[]{
                    Values.pointValue(CoordinateReferenceSystem.Cartesian, -10, -10),
                    Values.pointValue(CoordinateReferenceSystem.Cartesian, 10, -10),
                    Values.pointValue(CoordinateReferenceSystem.Cartesian, 10, 10),
                    Values.pointValue(CoordinateReferenceSystem.Cartesian, 0, 20),
                    Values.pointValue(CoordinateReferenceSystem.Cartesian, -10, 10)
            });
            simplePolygon = Neo4jArrayToInMemoryConverter.convertToInMemoryPolygon(node);
            tx.commit();
        }

        assertThat("expected polygon to contain 6 points", simplePolygon.getPoints().length, equalTo(6));
        assertThat("expected polygon to contain correct coordinates on pos 1", simplePolygon.getPoints()[0].getCoordinate(), equalTo(new double[]{-10, -10}));
        assertThat("expected polygon to contain correct coordinates on pos 2", simplePolygon.getPoints()[1].getCoordinate(), equalTo(new double[]{10, -10}));
        assertThat("expected polygon to contain correct coordinates on pos 3", simplePolygon.getPoints()[2].getCoordinate(), equalTo(new double[]{10, 10}));
        assertThat("expected polygon to contain correct coordinates on pos 4", simplePolygon.getPoints()[3].getCoordinate(), equalTo(new double[]{0, 20}));
        assertThat("expected polygon to contain correct coordinates on pos 5", simplePolygon.getPoints()[4].getCoordinate(), equalTo(new double[]{-10, 10}));
        assertThat("expected polygon to contain correct coordinates on pos 6", simplePolygon.getPoints()[5].getCoordinate(), equalTo(new double[]{-10, -10}));
    }

    @Test
    public void shouldTraverseSingleWayPolygon() {
        Polygon.SimplePolygon simplePolygon;
        long osmRelationId = 1;

        int n = 10;
        Node[] wayNodes = new Node[n];
        Node[] nodes = new Node[n];
        double[][] points = getPoints(n);

        try (Transaction tx = db.beginTx()) {
            for (int i = 0; i < n; i++) {
                wayNodes[i] = tx.createNode();
                nodes[i] = tx.createNode();

                PointValue point;
                int half = n / 2;
                if (i < half) {
                    point = Values.pointValue(CoordinateReferenceSystem.Cartesian, points[i][0], points[i][1]);
                } else {
                    point = Values.pointValue(CoordinateReferenceSystem.Cartesian, points[i][0], points[i][1]);
                }

                nodes[i].setProperty("location", point);
                wayNodes[i].createRelationshipTo(nodes[i], Relation.NODE);
            }

            for (int i = 0; i < n; i++) {
                wayNodes[i].createRelationshipTo(wayNodes[(i + 1) % (n)], Relation.NEXT);
            }

            simplePolygon = new Neo4jSimpleGraphNodePolygon(wayNodes[0], osmRelationId);

            org.neo4j.spatial.core.Point[] polygonPoints = simplePolygon.getPoints();
            for (int i = 0; i < polygonPoints.length; i++) {
                assertThat(polygonPoints[i].getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(polygonPoints[i].getCoordinate()[1], equalTo(points[i % n][1]));
            }

            simplePolygon.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 2));
            int i = 0;
            while (!simplePolygon.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = simplePolygon.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[i % n][1]));
                i++;
            }

            simplePolygon.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 0));
            i = n;
            while (!simplePolygon.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = simplePolygon.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[i % n][1]));
                i--;
            }

            simplePolygon.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 8), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 8));
            i = 5;
            while (!simplePolygon.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = simplePolygon.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[i % n][1]));
                i = ((i-1) % (n) + (n)) % (n);
            }

            tx.commit();
        }
    }

    @Test
    public void shouldTraverseTwoWayPolygon() {
        Polygon.SimplePolygon simplePolygon;
        long osmRelationId = 1;

        int n = 10;
        Node[] wayNodes = new Node[n+2];
        Node[] nodes = new Node[n];
        double[][] points = getPoints(n);

        try (Transaction tx = db.beginTx()) {
            for (int i = 0; i < n; i++) {
                wayNodes[i] = tx.createNode();
                nodes[i] = tx.createNode();

                PointValue point;
                int half = n / 2;
                if (i < half) {
                    point = Values.pointValue(CoordinateReferenceSystem.Cartesian, points[i][0], points[i][1]);
                } else {
                    point = Values.pointValue(CoordinateReferenceSystem.Cartesian, points[i][0], points[i][1]);
                }

                nodes[i].setProperty("location", point);
                wayNodes[i].createRelationshipTo(nodes[i], Relation.NODE);
            }

            wayNodes[n] = tx.createNode();
            wayNodes[n+1] = tx.createNode();

            for (int i = 0; i < n/2-1; i++) {
                wayNodes[i].createRelationshipTo(wayNodes[(i + 1) % (n)], Relation.NEXT);
            }

            for (int i = n/2; i < n; i++) {
                wayNodes[i].createRelationshipTo(wayNodes[(i + 1) % (wayNodes.length)], Relation.NEXT);
            }

            wayNodes[n].createRelationshipTo(nodes[0], Relation.NODE);
            wayNodes[n-1].createRelationshipTo(wayNodes[n], Relation.NEXT);
            wayNodes[n].createRelationshipTo(wayNodes[0], Relation.NEXT_IN_POLYGON).setProperty("relation_osm_ids", new long[]{osmRelationId});

            int a = n+1;
            wayNodes[a].createRelationshipTo(nodes[n/2], Relation.NODE);
            wayNodes[n/2-1].createRelationshipTo(wayNodes[a], Relation.NEXT);
            wayNodes[a].createRelationshipTo(wayNodes[n/2], Relation.NEXT_IN_POLYGON).setProperty("relation_osm_ids", new long[]{osmRelationId});

            simplePolygon = new Neo4jSimpleGraphNodePolygon(wayNodes[0], osmRelationId);
            int idx;

            org.neo4j.spatial.core.Point[] polygonPoints = simplePolygon.getPoints();
            for (int i = 0; i < polygonPoints.length; i++) {
                assertThat(polygonPoints[i].getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(polygonPoints[i].getCoordinate()[1], equalTo(points[i % n][1]));
            }
            assertThat(polygonPoints.length, equalTo(n+1)); //n+1 iterations

            simplePolygon.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 2));
            idx = 0;
            while (!simplePolygon.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = simplePolygon.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[idx % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[idx % n][1]));
                idx++;
            }
            assertThat(idx, equalTo(n+1)); //n+1 iterations


            simplePolygon.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 0));
            idx = n;
            while (!simplePolygon.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = simplePolygon.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[idx % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[idx % n][1]));
                idx--;
            }
            assertThat(idx, equalTo(-1)); //n+1 iterations


            simplePolygon.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 8), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 8));
            idx = 5;
            while (!simplePolygon.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = simplePolygon.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[idx % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[idx % n][1]));
                idx = ((idx-1) % (n) + (n)) % (n);
            }
            assertThat(idx, equalTo(4)); //n+1 iterations

            tx.commit();
        }
    }

    @Test
    public void shouldTraverseSingleWayPolyline() {
        Polyline polyline;
        long osmRelationId = 1;

        int n = 10;
        Node[] wayNodes = new Node[n];
        Node[] nodes = new Node[n];
        double[][] points = getPoints(n);

        try (Transaction tx = db.beginTx()) {
            for (int i = 0; i < n; i++) {
                wayNodes[i] = tx.createNode();
                nodes[i] = tx.createNode();

                PointValue point = Values.pointValue(CoordinateReferenceSystem.Cartesian, points[i][0], points[i][1]);

                System.out.printf("%s: %s\n", wayNodes[i], Arrays.toString(points[i]));

                nodes[i].setProperty("location", point);
                wayNodes[i].createRelationshipTo(nodes[i], Relation.NODE);
            }

            for (int i = 0; i < n-1; i++) {
                wayNodes[i].createRelationshipTo(wayNodes[i + 1], Relation.NEXT);
            }

            polyline = new Neo4jSimpleGraphNodePolyline(wayNodes[0], osmRelationId);

            org.neo4j.spatial.core.Point[] polylinePoints = polyline.getPoints();
            for (int i = 0; i < polylinePoints.length; i++) {
                assertThat(polylinePoints[i].getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(polylinePoints[i].getCoordinate()[1], equalTo(points[i % n][1]));
            }

            polyline.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 2));
            int i = 0;
            while (!polyline.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = polyline.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[i % n][1]));
                i++;
            }

            polyline.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 2));
            i = n-1;
            while (!polyline.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = polyline.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[i % n][1]));
                i--;
            }

            polyline.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 8), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 8));
            i = 5;
            while (!polyline.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = polyline.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[i % n][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[i % n][1]));
                i--;
            }

            tx.commit();
        }
    }

    @Test
    public void shouldTraverseTwoWayPolyline() {
        Polyline polyline;
        long osmRelationId = 1;

        int n = 10;
        Node[] wayNodes = new Node[n+1];
        Node[] nodes = new Node[n];
        double[][] points = getPoints(n);

        try (Transaction tx = db.beginTx()) {
            for (int i = 0; i < n; i++) {
                wayNodes[i] = tx.createNode();
                nodes[i] = tx.createNode();

                PointValue point = Values.pointValue(CoordinateReferenceSystem.Cartesian, points[i][0], points[i][1]);

                nodes[i].setProperty("location", point);
                wayNodes[i].createRelationshipTo(nodes[i], Relation.NODE);
            }

            wayNodes[n] = tx.createNode();

            for (int i = 0; i < n/2-1; i++) {
                wayNodes[i].createRelationshipTo(wayNodes[i + 1], Relation.NEXT);
            }

            for (int i = n/2+1; i < n-1; i++) {
                wayNodes[i].createRelationshipTo(wayNodes[i + 1], Relation.NEXT);
            }

            wayNodes[n].createRelationshipTo(nodes[n/2], Relation.NODE);
            wayNodes[n/2-1].createRelationshipTo(wayNodes[n], Relation.NEXT);
            wayNodes[n].createRelationshipTo(wayNodes[n/2+1], Relation.NEXT_IN_POLYLINE).setProperty("relation_osm_ids", new long[]{osmRelationId});

            polyline = new Neo4jSimpleGraphNodePolyline(wayNodes[0], osmRelationId);
            int idx;

            org.neo4j.spatial.core.Point[] polylinePoints = polyline.getPoints();
            for (int i = 0; i < polylinePoints.length; i++) {
                assertThat(polylinePoints[i].getCoordinate()[0], equalTo(points[i][0]));
                assertThat(polylinePoints[i].getCoordinate()[1], equalTo(points[i][1]));
            }
            assertThat(polylinePoints.length, equalTo(n)); //n iterations

            polyline.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 2));
            idx = 0;
            while (!polyline.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = polyline.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[idx][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[idx][1]));
                idx++;
            }
            assertThat(idx, equalTo(n)); //n iterations


            polyline.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 0), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 2));
            idx = n-1;
            while (!polyline.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = polyline.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[idx][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[idx][1]));
                idx--;
            }
            assertThat(idx, equalTo(-1)); //n iterations


            polyline.startTraversal(org.neo4j.spatial.core.Point.point(CRS.Cartesian, 0, 8), org.neo4j.spatial.core.Point.point(CRS.Cartesian, 5, 8));
            idx = 5;
            while (!polyline.fullyTraversed()) {
                org.neo4j.spatial.core.Point point = polyline.getNextPoint();
                assertThat(point.getCoordinate()[0], equalTo(points[idx][0]));
                assertThat(point.getCoordinate()[1], equalTo(points[idx][1]));
                idx--;
            }
            assertThat(idx, equalTo(-1)); //6 iterations

            tx.commit();
        }
    }

    private double[][] getPoints(int n) {
        double[][] points = new double[n][2];

        for (int i = 0; i < n; i++) {
            int half = n / 2;
            if (i < half) {
                points[i] = new double[]{5, i*2};
            } else {
                points[i] = new double[]{0, n - (i+1-half)*2};
            }
        }
        return points;
    }
}
