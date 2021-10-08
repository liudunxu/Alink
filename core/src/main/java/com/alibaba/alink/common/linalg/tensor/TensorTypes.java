package com.alibaba.alink.common.linalg.tensor;

import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;

public class TensorTypes {

	public static final TypeInformation <Tensor <?>> TENSOR = TypeInformation.of(new TypeHint <Tensor <?>>() {});
	public static final TypeInformation <BoolTensor> BOOL_TENSOR = TypeInformation.of(BoolTensor.class);
	public static final TypeInformation <ByteTensor> BYTE_TENSOR = TypeInformation.of(ByteTensor.class);
	public static final TypeInformation <UByteTensor> UBYTE_TENSOR = TypeInformation.of(UByteTensor.class);
	public static final TypeInformation <DoubleTensor> DOUBLE_TENSOR = TypeInformation.of(DoubleTensor.class);
	public static final TypeInformation <FloatTensor> FLOAT_TENSOR = TypeInformation.of(FloatTensor.class);
	public static final TypeInformation <IntTensor> INT_TENSOR = TypeInformation.of(IntTensor.class);
	public static final TypeInformation <LongTensor> LONG_TENSOR = TypeInformation.of(LongTensor.class);
	public static final TypeInformation <StringTensor> STRING_TENSOR = TypeInformation.of(StringTensor.class);

}