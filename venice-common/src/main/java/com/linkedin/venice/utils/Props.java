package com.linkedin.venice.utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;


public class Props implements Map<String, String> {

  private final Map<String, String> props;

  public Props() {
    this.props = new HashMap<String, String>();
  }

  public Props(File... files)
      throws FileNotFoundException, IOException {
    this.props = new HashMap<String, String>();
    for (int i = files.length - 1; i >= 0; i--) {
      Properties properties = new Properties();
      InputStream input = new BufferedInputStream(new FileInputStream(files[i].getAbsolutePath()));
      properties.load(input);
      for (Entry<Object, Object> e : properties.entrySet()) {
        this.props.put((String) e.getKey(), (String) e.getValue());
      }
      input.close();
    }
  }

  public Props(Map<String, String>... props) {
    this.props = new HashMap<String, String>();
    for (int i = props.length - 1; i >= 0; i--) {
      this.props.putAll(props[i]);
    }
  }

  public Props(Properties... properties) {
    this.props = new HashMap<String, String>();
    loadProperties(properties);
  }

  public void loadProperties(Properties... properties) {
    for (int i = properties.length - 1; i >= 0; i--) {
      for (Entry<Object, Object> e : properties[i].entrySet()) {
        this.props.put((String) e.getKey(), (String) e.getValue());
      }
    }
  }

  public Props mergeWithProperties(Props properties) {
    Props mergedProps = new Props();
    for (Entry<String, String> entry : this.entrySet()) {
      mergedProps.put(entry.getKey(), entry.getValue());
    }
    for (Entry<String, String> entry : properties.entrySet()) {
      mergedProps.put(entry.getKey(), entry.getValue());
    }
    return mergedProps;
  }

  @Override
  public void clear() {
    props.clear();
  }

  @Override
  public boolean containsKey(Object k) {
    return props.containsKey(k);
  }

  @Override
  public boolean containsValue(Object value) {
    return props.containsValue(value);
  }

  @Override
  public Set<Entry<String, String>> entrySet() {
    return props.entrySet();
  }

  @Override
  public String get(Object key) {
    return props.get(key);
  }

  @Override
  public boolean isEmpty() {
    return props.isEmpty();
  }

  @Override
  public Set<String> keySet() {
    return props.keySet();
  }

  @Override
  public String put(String key, String value) {
    return props.put(key, value);
  }

  public String put(String key, Integer value) {
    return props.put(key, value.toString());
  }

  public String put(String key, Long value) {
    return props.put(key, value.toString());
  }

  public String put(String key, Double value) {
    return props.put(key, value.toString());
  }

  public Props with(String key, String value) {
    put(key, value);
    return this;
  }

  public Props with(String key, Integer value) {
    put(key, value);
    return this;
  }

  public Props with(String key, Double value) {
    put(key, value);
    return this;
  }

  public Props with(String key, Long value) {
    put(key, value);
    return this;
  }

  @Override
  public void putAll(Map<? extends String, ? extends String> m) {
    props.putAll(m);
  }

  @Override
  public String remove(Object s) {
    return props.remove(s);
  }

  @Override
  public int size() {
    return props.size();
  }

  @Override
  public Collection<String> values() {
    return props.values();
  }

  public String getString(String key, String defaultValue) {
    if (containsKey(key)) {
      return get(key);
    } else {
      return defaultValue;
    }
  }

  public String getString(String key) {
    if (containsKey(key)) {
      return get(key);
    } else {
      throw new UndefinedPropertyException(key);
    }
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    if (containsKey(key)) {
      return "true".equalsIgnoreCase(get(key));
    } else {
      return defaultValue;
    }
  }

  public boolean getBoolean(String key) {
    if (containsKey(key)) {
      return "true".equalsIgnoreCase(get(key));
    } else {
      throw new UndefinedPropertyException(key);
    }
  }

  public long getLong(String name, long defaultValue) {
    if (containsKey(name)) {
      return Long.parseLong(get(name));
    } else {
      return defaultValue;
    }
  }

  public long getLong(String name) {
    if (containsKey(name)) {
      return Long.parseLong(get(name));
    } else {
      throw new UndefinedPropertyException(name);
    }
  }

  public int getInt(String name, int defaultValue) {
    if (containsKey(name)) {
      return Integer.parseInt(get(name));
    } else {
      return defaultValue;
    }
  }

  public int getInt(String name) {
    if (containsKey(name)) {
      return Integer.parseInt(get(name));
    } else {
      throw new UndefinedPropertyException(name);
    }
  }

  public double getDouble(String name, double defaultValue) {
    if (containsKey(name)) {
      return Double.parseDouble(get(name));
    } else {
      return defaultValue;
    }
  }

  public double getDouble(String name) {
    if (containsKey(name)) {
      return Double.parseDouble(get(name));
    } else {
      throw new UndefinedPropertyException(name);
    }
  }

  public long getBytes(String name, long defaultValue) {
    if (containsKey(name)) {
      return getBytes(name);
    } else {
      return defaultValue;
    }
  }

  @Override
  public boolean equals(Object o) {
    return this.props.equals(o);
  }

  @Override
  public int hashCode() {
    return this.props.hashCode();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("{");
    for (Entry<String, String> entry : this.props.entrySet()) {
      builder.append(entry.getKey());
      builder.append(": ");
      builder.append(entry.getValue());
      builder.append(", ");
    }
    builder.append("}");
    return builder.toString();
  }

  public long getBytes(String name) {
    if (!containsKey(name)) {
      throw new UndefinedPropertyException(name);
    }

    String bytes = get(name);
    String bytesLc = bytes.toLowerCase().trim();
    if (bytesLc.endsWith("kb")) {
      return Long.parseLong(bytes.substring(0, bytes.length() - 2)) * 1024;
    } else if (bytesLc.endsWith("k")) {
      return Long.parseLong(bytes.substring(0, bytes.length() - 1)) * 1024;
    } else if (bytesLc.endsWith("mb")) {
      return Long.parseLong(bytes.substring(0, bytes.length() - 2)) * 1024 * 1024;
    } else if (bytesLc.endsWith("m")) {
      return Long.parseLong(bytes.substring(0, bytes.length() - 1)) * 1024 * 1024;
    } else if (bytesLc.endsWith("gb")) {
      return Long.parseLong(bytes.substring(0, bytes.length() - 2)) * 1024 * 1024 * 1024;
    } else if (bytesLc.endsWith("g")) {
      return Long.parseLong(bytes.substring(0, bytes.length() - 1)) * 1024 * 1024 * 1024;
    } else {
      return Long.parseLong(bytes);
    }
  }

  public List<String> getList(String key, List<String> defaultValue) {
    if (!containsKey(key)) {
      return defaultValue;
    }

    String value = get(key);
    String[] pieces = value.split("\\s*,\\s*");
    return Arrays.asList(pieces);
  }

  public List<String> getList(String key) {
    if (!containsKey(key)) {
      throw new UndefinedPropertyException(key);
    }
    return getList(key, null);
  }
}
