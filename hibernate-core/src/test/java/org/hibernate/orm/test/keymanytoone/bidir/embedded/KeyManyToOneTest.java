/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.keymanytoone.bidir.embedded;

import java.util.List;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Root;

import org.hibernate.stat.spi.StatisticsImplementor;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * @author Steve Ebersole
 */
@DomainModel(
		xmlMappings = "org/hibernate/orm/test/keymanytoone/bidir/embedded/Mapping.hbm.xml"
)
@SessionFactory(
		generateStatistics = true
)
public class KeyManyToOneTest {

	@AfterEach
	public void tearDown(SessionFactoryScope scope){
		scope.inTransaction(
				session -> {
					session.createQuery( "delete from Order" ).executeUpdate();
					session.createQuery( "delete from Customer" ).executeUpdate();
				}
		);
	}

	@Test
	public void testCriteriaRestrictionOnKeyManyToOne(SessionFactoryScope scope) {
		scope.inTransaction( s -> {
			s.createQuery( "from Order o where o.customer.name = 'Acme'" ).list();
//		Criteria criteria = s.createCriteria( Order.class );
//		criteria.createCriteria( "customer" ).add( Restrictions.eq( "name", "Acme" ) );
//		criteria.list();
			CriteriaBuilder criteriaBuilder = s.getCriteriaBuilder();
			CriteriaQuery<Order> criteria = criteriaBuilder.createQuery( Order.class );
			Root<Order> root = criteria.from( Order.class );
			Join<Object, Object> customer = root.join( "customer", JoinType.INNER );
			criteria.where( criteriaBuilder.equal( customer.get( "name" ), "Acme" ) );
			s.createQuery( criteria ).list();
		} );
	}

	@Test
	public void testSaveCascadedToKeyManyToOne(SessionFactoryScope scope) {
		// test cascading a save to an association with a key-many-to-one which refers to a
		// just saved entity
		scope.inTransaction( s -> {
			Customer cust = new Customer( "Acme, Inc." );
			Order order = new Order( cust, 1 );
			cust.getOrders().add( order );
			getStatistics( scope ).clear();
			s.save( cust );
			s.flush();
			assertThat( getStatistics( scope ).getEntityInsertCount(), is( 2L ) );
			s.delete( cust );
		} );
	}

	@Test
	public void testQueryingOnMany2One(SessionFactoryScope scope) {
		Customer cust = new Customer( "Acme, Inc." );
		Order order = new Order( cust, 1 );
		scope.inTransaction( s -> {
			cust.getOrders().add( order );
			s.save( cust );
		} );

		scope.inTransaction( s -> {
			List results = s.createQuery( "from Order o where o.customer.name = :name" )
					.setParameter( "name", cust.getName() )
					.list();
			assertThat( results.size(), is( 1 ) );
		} );

		scope.inTransaction( s -> {
			s.delete( cust );
		} );
	}

	@Test
	public void testLoadingStrategies(SessionFactoryScope scope) {
		Customer cusotmer = scope.fromTransaction(
				session -> {
					Customer cust = new Customer( "Acme, Inc." );
					Order order = new Order( cust, 1 );
					cust.getOrders().add( order );
					session.save( cust );
					return cust;
				}
		);

		scope.inTransaction(
				session -> {
					Customer cust = session.get( Customer.class, cusotmer.getId() );
					assertThat( cust.getOrders().size(), is( 1 ) );
					session.clear();

					cust = (Customer) session.createQuery( "from Customer" ).uniqueResult();
					assertThat( cust.getOrders().size(), is( 1 ) );
					session.clear();

					cust = (Customer) session.createQuery( "from Customer c join fetch c.orders" ).uniqueResult();
					assertThat( cust.getOrders().size(), is( 1 ) );
					session.clear();

					session.delete( cust );
				}
		);
	}

	private StatisticsImplementor getStatistics(SessionFactoryScope scope) {
		return scope.getSessionFactory().getStatistics();
	}

}
