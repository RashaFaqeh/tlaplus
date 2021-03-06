package org.lamport.tla.toolbox.tool.tlc.output.data;

import java.util.List;

public class TLCSetVariableValue extends TLCVariableValue
{

    private static final String[] DELIMETERS = { "{", ",", "}" };

    TLCSetVariableValue(List values)
    {
        this.value = values;

    }

    public Object getValue()
    {
        return getElements();
    }

    public TLCVariableValue[] getElements()
    {
        List list = (List) value;
        return (TLCVariableValue[]) list.toArray(new TLCVariableValue[list.size()]);
    }

    public String toSimpleString()
    {
        TLCVariableValue[] elements = getElements();
        return arrayToSimpleStringBuffer(elements, DELIMETERS).toString();
    }
}
