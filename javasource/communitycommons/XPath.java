package communitycommons;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class XPath<T>
{
	/** Build in tokens, see: https://world.mendix.com/display/refguide3/XPath+Keywords+and+System+Variables
	 * 
	 */
	
	public static final String CurrentUser =  "[%CurrentUser%]";
	public static final String CurrentObject =  "[%CurrentObject%]";

	public static final String CurrentDateTime =  "[%CurrentDateTime%]";
	public static final String BeginOfCurrentDay =  "[%BeginOfCurrentDay%]";
	public static final String EndOfCurrentDay =  "[%EndOfCurrentDay%]";
	public static final String BeginOfCurrentHour =  "[%BeginOfCurrentHour%]";
	public static final String EndOfCurrentHour =  "[%EndOfCurrentHour%]";
	public static final String BeginOfCurrentMinute =  "[%BeginOfCurrentMinute%]";
	public static final String EndOfCurrentMinute =  "[%EndOfCurrentMinute%]";
	public static final String BeginOfCurrentMonth =  "[%BeginOfCurrentMonth%]";
	public static final String EndOfCurrentMonth =  "[%EndOfCurrentMonth%]";
	public static final String BeginOfCurrentWeek =  "[%BeginOfCurrentWeek%]";
	public static final String EndOfCurrentWeek =  "[%EndOfCurrentWeek%]";

	public static final String DayLength =  "[%DayLength%]";
	public static final String HourLength =  "[%HourLength%]";
	public static final String MinuteLength =  "[%MinuteLength%]";
	public static final String SecondLength =  "[%SecondLength%]";
	public static final String WeekLength =  "[%WeekLength%]";
	public static final String YearLength =  "[%YearLength%]";
	public static final String ID	= "id";
	
	/** End builtin tokens */
	
	private String	entity;
	private int	offset = 0;
	private int	limit = -1;
	private LinkedHashMap<String, String> sorting = new LinkedHashMap<String, String>(); //important, linked map!
	private LinkedList<String> closeStack = new LinkedList<String>();
	private StringBuffer builder = new StringBuffer();
	private IContext	context;
	private Class<T>	proxyClass;
	private boolean requiresBinOp = false; //state property, indicates whether and 'and' needs to be inserted before the next constraint
	
	public static XPath<IMendixObject> create(IContext c, String entityType) {
		XPath<IMendixObject> res = new XPath<IMendixObject>(c, IMendixObject.class);
		
		res.entity = entityType;
		
		return res;
	}
	
	public static <U> XPath<U> create(IContext c, Class<U> proxyClass) {
		return new XPath<U>(c, proxyClass);
	}
	
	private XPath(IContext c, Class<T> proxyClass) {
		try
		{
			if (proxyClass != IMendixObject.class)
				this.entity = (String) proxyClass.getMethod("getType").invoke(null);
		}
		catch (Exception e)
		{
			throw new IllegalArgumentException("Failed to determine entity type of proxy class. Did you provide a valid proxy class? '" + proxyClass.getName() + "'");
		}

		this.proxyClass = proxyClass;
		this.context = c;
	}
	
	private XPath<T> autoInsertAnd() {
		if (requiresBinOp)
			and();
		return this;
	}
	
	private XPath<T> requireBinOp(boolean requires) {
		requiresBinOp = requires;
		return this;
	}
	
	public XPath<T> offset(int offset2) {
		if (offset2 < 0)
			throw new IllegalArgumentException("Offset should not be negative");
		this.offset  = offset2;
		return this;
	}
	
	public XPath<T> limit(int limit2) {
		if (limit2 < -1 || limit2 == 0)
			throw new IllegalArgumentException("Limit should be larger than zero or -1. ");

		this.limit = limit2;
		return this;
	}
	
	public XPath<T> addSortingAsc(Object... sortparts) {
		assertOdd(sortparts);
		sorting.put(StringUtils.join(sortparts, '/'), "asc");
		return this;
	}
	
	public XPath<T> addSortingDesc(Object... sortparts) {
		sorting.put(StringUtils.join(sortparts, "/"), "desc");
		return this;
	}	
	
	public XPath<T> eq(Object attr, Object valuecomparison) {
		return compare(attr, "=", valuecomparison);
	}
	
	public XPath<T> eq(Object... pathAndValue) {
		assertEven(pathAndValue);
		return compare(Arrays.copyOfRange(pathAndValue, 0, pathAndValue.length -1), "=", pathAndValue[pathAndValue.length -1 ]);
	}
	
	public XPath<T> equalsIgnoreCase(Object attr, String value) {
		//(contains(Name, $email) and length(Name) = length($email)
		return 
			subconstraint()
			.contains(attr, value)
			.and()
			.append(" length(" + attr + ") = ").append(value == null ? "0" : valueToXPathValue(value.length()))	
			.close();
	}
	
	public XPath<T> notEq(Object attr, Object valuecomparison) {
		return compare(attr, "!=", valuecomparison);
	}
	
	public XPath<T> notEq(Object... pathAndValue) {
		assertEven(pathAndValue);
		return compare(Arrays.copyOfRange(pathAndValue, 0, pathAndValue.length -1), "!=", pathAndValue[pathAndValue.length -1 ]);
	}

	public XPath<T> contains(Object attr, String value)
	{
		autoInsertAnd().append(" contains(").append(String.valueOf(attr)).append(",").append(valueToXPathValue(value)).append(") ");
		return this.requireBinOp(true);
	}
	
	public XPath<T> compare(Object attr, String operator, Object value) {
		return compare(new Object[] {attr}, operator, value);
	}
	
	public XPath<T> compare(Object[] path, String operator, Object value) {
		assertOdd(path);
		autoInsertAnd().append(StringUtils.join(path, '/')).append(" ").append(operator).append(" ").append(valueToXPathValue(value));
		return this.requireBinOp(true);
	}
	
	public XPath<T> hasReference(Object... path)
	{
		assertEven(path); //Reference + entity type
		autoInsertAnd().append(StringUtils.join(path, '/'));
		return this.requireBinOp(true);
	}

	public XPath<T> subconstraint(Object... path) {
		assertEven(path);
		autoInsertAnd().append(StringUtils.join(path, '/')).append("[");
		closeStack.push("]");
		return this.requireBinOp(false);
	}	
	
	public XPath<T> subconstraint() {
		autoInsertAnd().append("(");
		closeStack.push(")");
		return this.requireBinOp(false);
	}
	
	public XPath<T> addConstraint()
	{
		if (!closeStack.isEmpty() && !closeStack.peek().equals("]"))
			throw new IllegalStateException("Cannot add a constraint while in the middle of something else..");
		
		return append("][").requireBinOp(false);
	}
	
	public XPath<T> close() {
		if (closeStack.isEmpty())
			throw new IllegalStateException("XPathbuilder close stack is empty!");
		append(closeStack.pop());
		return requireBinOp(true);
		//MWE: note that a close does not necessary require a binary operator, for example with two subsequent block([bla][boe]) constraints, 
		//but openening a binary constraint reset the flag, so that should be no issue
	}
	
	public XPath<T> or() {
		if (!requiresBinOp)
			throw new IllegalStateException("Received 'or' but no binary operator was expected");
		return append(" or ").requireBinOp(false);
	}
	
	public XPath<T> and() {
		if (!requiresBinOp)
			throw new IllegalStateException("Received 'and' but no binary operator was expected");
		return append(" and ").requireBinOp(false);
	}
	
	public XPath<T> not() {
		autoInsertAnd();
		closeStack.push(")");
		return append(" not(").requireBinOp(false);
	}
	
	private void assertOdd(Object[] stuff) {
		if (stuff == null || stuff.length == 0 || stuff.length % 2 == 0)
			throw new IllegalArgumentException("Expected an odd number of xpath path parts");
	}
	
	private void assertEven(Object[] stuff) {
		if (stuff == null || stuff.length == 0 || stuff.length % 2 == 1)
			throw new IllegalArgumentException("Expected an even number of xpath path parts");
	}
	
	public XPath<T> append(String s) {
		builder.append(s);
		return this;
	}
	
	public String getXPath() {
	
		if (builder.length() > 0)
			return "//" + this.entity + "[" + builder.toString() + "]";
		return "//" + this.entity;
	}
	
	private void assertEmptyStack() throws IllegalStateException
	{
		if (!closeStack.isEmpty())
			throw new IllegalStateException("Invalid xpath expression, not all items where closed");
	}
	
	public long count( ) throws CoreException {
		assertEmptyStack();

		return Core.retrieveXPathQueryAggregate(context, "count(" + getXPath() +")");
	}

	
	public IMendixObject firstMendixObject() throws CoreException {
		assertEmptyStack();
		
		List<IMendixObject> result = Core.retrieveXPathQuery(context, getXPath(), 1, offset, sorting);
		if (result.isEmpty())
			return null;
		return result.get(0);
	}
	
	public T first() throws CoreException {
		return createProxy(context, proxyClass, firstMendixObject());
	}
	

	/**
	 * Given a set of attribute names and values, tries to find the first object that matches all conditions, or creates one
	 * 
	 * @param autoCommit: whether the object should be committed once created (default: true)
	 * @param keysAndValues
	 * @return
	 * @throws CoreException 
	 */
	public T findOrCreateNoCommit(Object... keysAndValues) throws CoreException
	{
		T res = findOrCreateHelper(keysAndValues);
		
		return res != null ? res : constructInstance(false, keysAndValues);
	}

	
	public T findOrCreate(Object... keysAndValues) throws CoreException {
		T res = findOrCreateHelper(keysAndValues);
		
		return res != null ? res : constructInstance(true, keysAndValues);

	}
	
	private T findOrCreateHelper(Object... keysAndValues)
			throws IllegalStateException, CoreException
	{
		if (builder.length() > 0)
			throw new IllegalStateException("Find or create can only be used on XPath which do not have constraints already");
		
		assertEven(keysAndValues);
		for(int i = 0; i < keysAndValues.length; i+= 2)
			eq(keysAndValues[i], keysAndValues[i + 1]);
		
		T res = this.first();
		return res;
	}
	

	/**
	 * Creates one instance of the type of this XPath query, and initializes the provided attributes to the provided values. 
	 * @param keysAndValues AttributeName, AttributeValue, AttributeName2, AttributeValue2... list. 
	 * @return
	 * @throws CoreException
	 */
	public T constructInstance(boolean autoCommit, Object... keysAndValues) throws CoreException
	{
		assertEven(keysAndValues);
		IMendixObject newObj = Core.create(context, this.entity);
		
		for(int i = 0; i < keysAndValues.length; i+= 2)
			newObj.setValue(context, String.valueOf(keysAndValues[i]), toMemberValue(keysAndValues[i + 1]));
		
		if (autoCommit)
			Core.commit(context, newObj);
		
		return createProxy(context, proxyClass, newObj);
	}
	
	/**
	 * Given a current collection of primitive values, checks if for each value in the collection an object in the database exists. 
	 * It creates a new object if needed, and removes any superfluos objects in the database that are no longer in the collection.  
	 * 
	 * @param currentCollection The collection that act as reference for the objects that should be in this database in the end. 
	 * @param comparisonAttribute The attribute that should store the value as decribed in the collection
	 * @param autoDelete Automatically remove any superfluous objects form the database
	 * @param keysAndValues Constraints that should hold for the set of objects that are deleted or created. Objects outside this constraint are not processed.
	 * 
	 * @return A pair of lists. The first list contains the newly created objects, the second list contains the objects that (should be or are) removed. 
	 * @throws CoreException
	 */
	public <U> ImmutablePair<List<T>, List<T>> syncDatabaseWithCollection(Collection<U> currentCollection, Object comparisonAttribute, boolean autoDelete, Object... keysAndValues) throws CoreException {
		if (builder.length() > 0)
			throw new IllegalStateException("syncDatabaseWithCollection can only be used on XPath which do not have constraints already");

		
		List<T> added = new ArrayList<T>();
		List<T> removed = new ArrayList<T>();
		
		Set<U> col = new HashSet<U>(currentCollection);
		
		for(int i = 0; i < keysAndValues.length; i+= 2)
			eq(keysAndValues[i], keysAndValues[i + 1]);
		
		for(IMendixObject existingItem : this.allMendixObjects()) {
			//Item is still available 
			if (col.remove(existingItem.getValue(context, String.valueOf(comparisonAttribute)))) 
				continue;
				
			//No longer available
			removed.add(createProxy(context, this.proxyClass, existingItem));
			if (autoDelete) 
				Core.remove(context, existingItem);
		}
		
		//Some items where not found in the database
		for(U value : col) {
			
			//In apache lang3, this would just be: ArrayUtils.addAll(keysAndValues, comparisonAttribute, value)
			Object[] args = new Object[keysAndValues.length + 2];
			for(int i = 0; i < keysAndValues.length; i++)
				args[i] = keysAndValues[i];
			args[keysAndValues.length] = comparisonAttribute;
			args[keysAndValues.length + 1] = value;
			
			T newItem = constructInstance(true, args);
			added.add(newItem);
		}
		
		//Oké, stupid, Pair is also only available in apache lang3, so lets use a simple pair implementation for now
		return ImmutablePair.of(added, removed);
	}
	
	public T firstOrWait(long timeoutMSecs) throws CoreException, InterruptedException
	{
		IMendixObject result = null;
		
		long start = System.currentTimeMillis();
		int  sleepamount = 200;
		int  loopcount = 0;
		
		while (result == null) {
			loopcount += 1;
			result = firstMendixObject();
			
			long now = System.currentTimeMillis();
			
			if (start + timeoutMSecs < now) //Time expired
				break;

			if (loopcount % 5 == 0)
				sleepamount *= 1.5;
			
			//not expired, wait a bit
			if (result == null)
				Thread.sleep(sleepamount);
		}
		
		return createProxy(context, proxyClass, result); 
	}
	
	
	public List<IMendixObject> allMendixObjects() throws CoreException {
		assertEmptyStack();

		return Core.retrieveXPathQuery(context, getXPath(), limit, offset, sorting);
	}
	
	public List<T> all() throws CoreException {
		List<T> res = new ArrayList<T>();
		for(IMendixObject o : allMendixObjects())
			res.add(createProxy(context, proxyClass, o));
		
		return res;
	}
	
	@Override
	public String toString() {
		return getXPath();
	}


	/** 
	 * 
	 * 
	 * Static utility functions 
	 * 
	 * 
	*/
	
	//cache for proxy constructors. Reflection is slow, so reuse as much as possible
	private static Map<String, Method> initializers = new HashMap<String, Method>();
	
	public static <T> List<T> createProxyList(IContext c, Class<T> proxieClass, List<IMendixObject> objects) {
		List<T> res = new ArrayList<T>();
		if (objects == null || objects.size() == 0)
			return res;
		
		for(IMendixObject o : objects)
			res.add(createProxy(c, proxieClass, o));
		
		return res;
	}
	
	public static <T> T createProxy(IContext c, Class<T> proxieClass, IMendixObject object) {
		//Borrowed from nl.mweststrate.pages.MxQ package
		
		if (object == null)
			return null;
		
		if (c == null || proxieClass == null)
			throw new IllegalArgumentException("[CreateProxy] No context or proxieClass provided. ");
		
		//jeuj, we expect IMendixObject's. Thats nice..
		if (proxieClass == IMendixObject.class)
			return proxieClass.cast(object); //.. since we can do a direct cast
		
		try {
			String entityType = object.getType();
			
			if (!initializers.containsKey(entityType)) { 
			
				String[] entType = object.getType().split("\\.");
				Class<?> realClass = Class.forName(entType[0].toLowerCase()+".proxies."+entType[1]);

				initializers.put(entityType, realClass.getMethod("initialize", IContext.class, IMendixObject.class));
			}
			
			//find constructor
			Method m = initializers.get(entityType);
			
			//create proxy object
			Object result = m.invoke(null, c, object);
			
			//cast, but check first is needed because the actual type might be a subclass of the requested type
			if (!proxieClass.isAssignableFrom(result.getClass()))
				throw new IllegalArgumentException("The type of the object ('" + object.getType() + "') is not (a subclass) of '" + proxieClass.getName()+"'");
			
			T proxie = proxieClass.cast(result);
			return proxie;
		}
		catch (Exception e) {
			throw new RuntimeException("Unable to instantiate proxie: " + e.getMessage(), e);
		}
	}
	
	public static String valueToXPathValue(Object value)
	{
		if (value == null)
			return "NULL"; 

		//Complex objects
		if (value instanceof IMendixIdentifier)
			return "'" + String.valueOf(((IMendixIdentifier) value).getGuid()) +  "'";
		if (value instanceof IMendixObject)
			return valueToXPathValue(((IMendixObject)value).getId());
		if (value instanceof List<?>)
			throw new IllegalArgumentException("List based values are not supported!");
		
		//Primitives
		if (value instanceof Date)
			return String.valueOf(((Date) value).getTime());
		if (value instanceof Long || value instanceof Integer)
			return String.valueOf(value);
		if (value instanceof Double || value instanceof Float) {
			//make sure xpath understands our number formatting
			NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
			format.setMaximumFractionDigits(10);
			format.setGroupingUsed(false);
			return format.format(value);
		}
		if (value instanceof Boolean) {
			return value.toString() + "()"; //xpath boolean, you know..
		}
		if (value instanceof String) {
			return "'" + StringEscapeUtils.escapeXml(String.valueOf(value)) + "'";
		}
		
		//Object, assume its a proxy and deproxiefy
		try
		{
			IMendixObject mo = proxyToMendixObject(value);
			return valueToXPathValue(mo);
		}
		catch (NoSuchMethodException e)
		{
			//This is O.K. just not a proxy object...
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to retrieve MendixObject from proxy: " + e.getMessage(), e);
		}
		
		//assume some string representation
		return "'" + StringEscapeUtils.escapeXml(String.valueOf(value)) + "'";
	}
	
	public static IMendixObject proxyToMendixObject(Object value)
		throws NoSuchMethodException, SecurityException, IllegalAccessException,
		IllegalArgumentException, InvocationTargetException
	{
		Method m = value.getClass().getMethod("getMendixObject");
		IMendixObject mo = (IMendixObject) m.invoke(value);
		return mo;
	}

	public static <T> List<IMendixObject> proxyListToMendixObjectList(
			List<T> objects) throws SecurityException, IllegalArgumentException, NoSuchMethodException, IllegalAccessException, InvocationTargetException
	{
		ArrayList<IMendixObject> res = new ArrayList<IMendixObject>(objects.size());
		for(T i : objects)
			res.add(proxyToMendixObject(i));
		return res;
	}
	
	public static Object toMemberValue(Object value)
	{
		if (value == null)
			return null; 

		//Complex objects
		if (value instanceof IMendixIdentifier)
			return value;
		if (value instanceof IMendixObject)
			return ((IMendixObject)value).getId();
		
		if (value instanceof List<?>)
			throw new IllegalArgumentException("List based values are not supported!");
		
		//Primitives
		if (   value instanceof Date 
				|| value instanceof Long 
				|| value instanceof Integer
			  || value instanceof Double 
			  || value instanceof Float
			  || value instanceof Boolean
			  || value instanceof String) {
			return value;
		}
		
		if (value.getClass().isEnum())
			return value.toString();

		//Object, assume its a proxy and deproxiefy
		try
		{
			Method m = value.getClass().getMethod("getMendixObject");
			IMendixObject mo = (IMendixObject) m.invoke(value);
			return toMemberValue(mo);
		}
		catch (NoSuchMethodException e)
		{
			//This is O.K. just not a proxy object...
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to convert object to IMendixMember compatible value '" + value + "': " + e.getMessage(), e);
		}
		
		throw new RuntimeException("Failed to convert object to IMendixMember compatible value: " + value);
	}
	
	public static interface IBatchProcessor<T> {
		public void onItem(T item, long offset, long total) throws Exception;
	}
	
	private static final class ParallelJobRunner<T> implements Callable<Boolean>
	{
		private final XPath<T> self;
		private final IBatchProcessor<T> batchProcessor;
		private final IMendixObject item;
		private long index;
		private long count;

		ParallelJobRunner(XPath<T> self, IBatchProcessor<T> batchProcessor, IMendixObject item, long index, long count)
		{
			this.self = self;
			this.batchProcessor = batchProcessor;
			this.item = item;
			this.index = index;
			this.count = count;
		}

		@Override
		public Boolean call()
		{
			try
			{
				batchProcessor.onItem(XPath.createProxy(Core.createSystemContext(), self.proxyClass, item), index, count); //mwe: hmm, many contexts..
				return true;
			}
			catch (Exception e)
			{
				throw new RuntimeException(String.format("Failed to execute batch on '%s' offset %d: %s", self.toString(), self.offset, e.getMessage()), e);
			}
		}
	}
	

	/**
	 * Retreives all items in this xpath query in batches of a limited size. 
	 * Not that this function does not start a new transaction for all the batches,
	 * rather, it just limits the number of objects being retrieved and kept in memory at the same time. 
	 * 
	 * So it only batches the retrieve process, not the optional manipulations done in the onItem method.  
	 * @param batchsize
	 * @param batchProcessor
	 * @throws CoreException
	 */
	public void batch(int batchsize, IBatchProcessor<T> batchProcessor) throws CoreException
	{
		if (this.sorting.isEmpty())
			this.addSortingAsc(XPath.ID);
		
		long count = this.count();
		
		int baseoffset = this.offset;
		int baselimit = this.limit;
		
		boolean useBaseLimit = baselimit > -1;
		
		this.offset(baseoffset);
		List<T> data;
		
		long i = 0;

		do {
			int newlimit = useBaseLimit ? Math.min(batchsize, baseoffset + baselimit - this.offset) : batchsize;
			if (newlimit == 0)
				break; //where done, no more data is needed
			
			this.limit(newlimit);
			data = this.all();

			for(T item : data) {
				i += 1;
				try
				{
					batchProcessor.onItem(item, i, Math.max(i, count));
				}
				catch (Exception e)
				{
					throw new RuntimeException(String.format("Failed to execute batch on '%s' offset %d: %s", this.toString(), this.offset, e.getMessage()), e);
				}
			}
			
			this.offset(this.offset + data.size());
		} while(data.size() > 0);
	}
	
	/**
	 * Batch with parallelization.
	 * 
	 * IMPORTANT NOTE: DO NOT USE THE CONTEXT OF THE XPATH OBJECT ITSELF INSIDE THE BATCH PROCESSOR!
	 * 
	 * Instead, use: Item.getContext(); !!
	 * 
	 * 
	 * @param batchsize
	 * @param threads
	 * @param batchProcessor
	 * @throws CoreException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	public void batch(int batchsize, int threads, final IBatchProcessor<T> batchProcessor)
		throws CoreException, InterruptedException, ExecutionException
	{
		if (this.sorting.isEmpty())
			this.addSortingAsc(XPath.ID);

		ExecutorService pool = Executors.newFixedThreadPool(threads);

		final long count = this.count();

		final XPath<T> self = this;
		ConversationLog cl = new ConversationLog("Community_Commons");

		int progress = 0;
		List<Future<?>> futures = new ArrayList<Future<?>>(batchsize); //no need to synchronize

		this.offset(0);
		this.limit(batchsize);

		List<IMendixObject> data = this.allMendixObjects();

		while (data.size() > 0)
		{

			for (final IMendixObject item : data)
			{
				futures.add(pool.submit(new ParallelJobRunner<T>(self, batchProcessor, item, progress, count)));
				progress += 1;
			}

			while (!futures.isEmpty())
				futures.remove(0).get(); //wait for all futures before proceeding to next iteration

			this.offset(this.offset + data.size());
			data = this.allMendixObjects();
		}

		if (pool.shutdownNow().size() > 0)
			throw new IllegalStateException("Not all tasks where finished!");

	}

	public static Class<?> getProxyClassForEntityName(String entityname)
	{
		{
			String [] parts = entityname.split("\\.");
			try
			{
				return Class.forName(parts[0].toLowerCase() + ".proxies." +  parts[1]);
			}
			catch (ClassNotFoundException e)
			{
				throw new RuntimeException("Cannot find class for entity: " + entityname + ": " + e.getMessage(), e);
			}
		}
	}

	public boolean deleteAll() throws CoreException
	{
		this.limit(1000);
		List<IMendixObject> objs = allMendixObjects();
		while (!objs.isEmpty()) {
			if (!Core.remove(context, objs.toArray(new IMendixObject[objs.size()]))) 
				return false; //TODO: throw?
			
			objs = allMendixObjects();
		}
		return true;
	}



}
